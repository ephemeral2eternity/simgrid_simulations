package agentMngt;

import java.util.*;
import java.lang.reflect.Array;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.simgrid.msg.Host;
import org.simgrid.msg.HostNotFoundException;
import org.simgrid.msg.HostFailureException;
import org.simgrid.msg.Msg;
import org.simgrid.msg.Task;
import org.simgrid.msg.MsgException;
import org.simgrid.msg.TimeoutException;
import org.simgrid.msg.Process;
import org.simgrid.msg.Comm;

public class clientAgent extends Process {

	private String clientName;
	private ArrayList<Comm> comms;
	private String cacheAgent;
	private ArrayList<String> videoServers;
	private Map<String, Integer> serverLevels;
	private Map<String, Double> serverQoE;
	private QoEComparator qoeCmp;
	private double[] curCoords = {0.0, 0.0};
	private double buf;
	private double startTime;
	private double playTime;
	private double freezeTime;
	static private double[] bitrates = {400.0, 628.0, 986.0, 1549.0, 2433.0, 3821.0, 6000.0};
	static private double CHUNKLEN = 5.0;
	
	public clientAgent(Host host, String name, String[] args) {
		super(host, name, args);
		this.clientName = host.getName();
		this.comms = new ArrayList<Comm>();
		this.serverLevels = new HashMap<String, Integer>();
		this.serverQoE = new HashMap<String, Double>();
		this.videoServers = new ArrayList<String>();
		this.buf = 0.0;
		this.freezeTime = 0.0;
	}

	public Comm request(String cacheServer, int num, int level) throws MsgException
	{
		double msgSz = 0;
		double computeDuration = 0;
		double time = Msg.getClock();
		StreamingTask request = new StreamingTask("Request", computeDuration, msgSz);
		request.setTime(time);
		request.setLevel(level);
		request.setChunkLen(CHUNKLEN);
		request.setNum(num);
		request.setIsRequest(true);
		Comm comm = request.isend(cacheServer);
		return comm;
	}

	public int findNextLevel(double bw)
	{
		int nextLv = 1;
		int i = 1;
		for (double rate : this.bitrates)
		{
			if (bw > rate) {
				nextLv = i;
			}
			i ++;
		}
		return nextLv;
	}

	public double findNextFreeze(double pBw, double buffer, int nextLevel)
	{
		double pDownloadTime = 0;
		double nextBitrate = this.bitrates[nextLevel - 1];
		pDownloadTime = nextBitrate * CHUNKLEN / pBw;

		double nextBuf = buffer + CHUNKLEN - pDownloadTime;
		double nextFreeze = 0;

		if (nextBuf < 0)
			nextFreeze = -nextBuf;

		return nextFreeze;
	}

	public String findNextServer(String curServer, double nextQoE)
	{	
		Collections.sort(this.videoServers, this.qoeCmp);
		String topServer = this.videoServers.get(0);
		double topQoE = this.serverQoE.get(topServer);
		if (topQoE < nextQoE)
			return curServer;
		else
			return topServer;
	}

	public double computeQoE(double freezingTime, int bitrateLevel)
	{
		double delta = 0.5;
		double q_freeze = 5.0, q_bitrate = 5.0;
		double maxBitrate = this.bitrates[this.bitrates.length - 1];
		double curBitrate = this.bitrates[bitrateLevel - 1];

		double[] c = {5.0, 6.3484, 4.4, 0.72134};
		double[] a = {1.3554, 40};

		if (freezingTime > 0)
			q_freeze = c[0] - c[1] / (1 + Math.pow((c[2]/freezingTime), c[3]));

		q_bitrate = a[0] * Math.log(a[1]*curBitrate/maxBitrate);

		double qoe = delta * q_freeze + (1 - delta) * q_bitrate;
		return qoe;
	}

	public void updateQoE(String server, double qoe)
	{
		double alpha = 0.6;   // A higher alpha discounts older observations faster.
		double preQoE = this.serverQoE.get(server);
		double newQoE = qoe * alpha + (1 - alpha) * preQoE;
		this.serverQoE.put(server, newQoE);
	}


	public void processResponse(Task recvTask) throws HostFailureException, MsgException {
		double curTime;
		double msgSz;
		double dTime, bw;
		double curQoE, curBuf, curPlayTime, nextQoE;
		double curFreezing = 0, nextFreezing = 0;
		String curServer, nextServer;
		int curLevel, nextLevel;
		int seq;
		int lvls = this.bitrates.length;

		StreamingTask recvSTask = (StreamingTask) recvTask;
		if (!recvSTask.getIsRequest())
		{
			curTime = Msg.getClock();
			if (recvSTask.getNum() == 0)
			{
				this.startTime = curTime;
			}
			curPlayTime = curTime - this.startTime - this.freezeTime;
			curBuf = recvSTask.getChunkLen() * (recvSTask.getNum() + 1) - curPlayTime;
			curServer = recvSTask.getSenderName();
			msgSz = recvSTask.getMessageSize();
			dTime = (curTime - recvSTask.getTime());
			curLevel = recvSTask.getLevel();
			this.serverLevels.put(curServer, curLevel);

			if (curBuf >= 0) {
				curFreezing = 0;
				this.buf = curBuf;
				this.playTime = curPlayTime;
			}
			else {
				curFreezing = -curBuf;
				this.freezeTime += curFreezing;	
				this.buf = 0;
				this.playTime = (recvSTask.getNum() + 1) * CHUNKLEN;
				nextLevel = 1;
			}

			// Predict user Quality of Experience.
			bw = (msgSz / dTime) / 1024;
			nextLevel = findNextLevel(bw);
			nextFreezing = findNextFreeze(bw, this.buf, nextLevel);
			curQoE = computeQoE(curFreezing, curLevel);
			nextQoE = computeQoE(nextFreezing, nextLevel);
			updateQoE(curServer, curQoE);

			// Find next best server to serve next chunk.
			nextServer = findNextServer(curServer, nextQoE);

			// If the client should switch server, get the level of bitrate as the level you get from last time.
			if (!nextServer.equals(curServer))
				nextLevel = this.serverLevels.get(nextServer);

			if (this.buf > CHUNKLEN * 6) {
				waitFor(CHUNKLEN);
			}
			else if ((this.buf < CHUNKLEN * 0.5) && (this.buf > 0)) {
				nextLevel = 1;
			}

			Msg.info("Played: " + this.playTime + "; Seq: " + recvSTask.getNum() + "; Server: " + curServer +  "; QoE: " + curQoE + "; BW: " + bw + " kbps");
			System.out.println(recvSTask.getNum() + ", " + curServer + ", "+ curQoE + ", " + bw);
			seq = recvSTask.getNum() + 1;
			if (seq < 720)
			{
				// System.out.println("Client selected level: " + nextLevel);
				this.request(nextServer, seq, nextLevel);
			}
		}
		else
		{
			Msg.info("Wrongly sent video request to a client " + this.clientName);
		}
	}

	public String printMap(Map<String, Integer> levels) {
		String record = "";
		Iterator iter = levels.entrySet().iterator();
		while (iter.hasNext()) {
			Map.Entry<String, Integer> ent = (Map.Entry<String, Integer>) iter.next();
			record = record + ent.getKey() + ", " + ent.getValue() + "\n";
		}
		return record;
	}

	public void main(String[] args) throws MsgException {
		int inputArgs = args.length;
		Task recvTask = null;
		int timeoutCnt = 0;
		
		if (inputArgs > 0)
		{
			try {
				this.cacheAgent = Host.getByName(args[0]).getName();
				this.videoServers.add(this.cacheAgent);
				this.serverLevels.put(this.cacheAgent, 7);
				this.serverQoE.put(this.cacheAgent, 5.0);
				for (int i = 1; i < inputArgs; i ++)
				{
					String server = Host.getByName(args[i]).getName();
					this.videoServers.add(server);
					this.serverLevels.put(server, 6);
					this.serverQoE.put(server, 4.0);
				}
			} catch (HostNotFoundException e) {
				Msg.info("Invalid deployment file: " + e.toString());
				System.exit(1);
			}
		}
		Msg.info(printMap(this.serverLevels));
		this.qoeCmp = new QoEComparator(this.serverQoE);
		this.comms.add(request(this.cacheAgent, 0, this.serverLevels.get(this.cacheAgent)));

		while (true){
			recvTask = null;	
			for (int i = 0; i < this.comms.size(); i ++) {
				try {
					if (this.comms.get(i).test()) {
						this.comms.remove(i);
						i --;
					}
				} catch (Exception e) {
					Msg.info("[Error] Message sent failure!!");
					this.comms.remove(i);
				}
			}
		
			try {
				recvTask = Task.receive(this.clientName, 1000);
			} catch (TimeoutException e) {
				// Msg.info("[Exception] Timeout exception in retrieving tasks!");
				timeoutCnt ++;
			}
	
			if (recvTask != null)
			{
				timeoutCnt = 0;
				Msg.info("Data Received!");
				if (recvTask instanceof StreamingTask)
				{
					processResponse(recvTask);
				}
			}

			// boolean converge = isConverge(this.peerDiffs, th);		
			if ((this.comms.size() == 0) && (timeoutCnt > 100))
				break;
		}	

		Msg.info("goodbye!");
	}
}
