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
	private LevelComparator lCmp;
	private double[] curCoords = {0.0, 0.0};
	private double buf;
	private double startTime;
	private double playTime;
	static private double[] bitrates = {400.0, 628.0, 986.0, 1549.0, 2433.0, 3821.0, 6000.0};
	static private double CHUNKLEN = 5.0;
	
	public clientAgent(Host host, String name, String[] args) {
		super(host, name, args);
		this.clientName = host.getName();
		this.comms = new ArrayList<Comm>();
		this.serverLevels = new HashMap<String, Integer>();
		this.videoServers = new ArrayList<String>();
		this.buf = 0.0;
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
		for (double rate : bitrates)
		{
			if (rate < bw)
				nextLv = i;
			i ++;
		}
		return nextLv;
	}

	public void processResponse(Task recvTask) throws HostFailureException, MsgException {
		double curTime;
		double msgSz;
		double dTime, bw;
		String curServer, nextServer, switchedServer;
		int curLevel, nextLevel, switchedLevel;
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
			this.playTime = curTime - this.startTime;
			this.buf = recvSTask.getChunkLen() * (recvSTask.getNum() + 1) - this.playTime;
			curServer = recvSTask.getSenderName();
			msgSz = recvSTask.getMessageSize();
			dTime = (curTime - recvSTask.getTime());
			curLevel = recvSTask.getLevel();
			this.serverLevels.put(curServer, curLevel);
			Collections.sort(this.videoServers, this.lCmp);
			bw = (msgSz*8 / dTime) / 1024;

			if (bw > bitrates[curLevel - 1])
			{
				nextLevel = ((curLevel + 1) >= lvls) ? lvls : (curLevel + 1);
			}
			else
			{
				nextLevel = findNextLevel(bw);
			}

			nextServer = curServer;
			switchedServer = this.videoServers.get(0);
			switchedLevel = this.serverLevels.get(nextServer);

			if (switchedLevel > nextLevel) {
				nextServer = switchedServer;
				nextLevel = switchedLevel;
			}
			if (this.buf > CHUNKLEN * 6) {
				waitFor(CHUNKLEN);
			}
			else if (this.buf < CHUNKLEN * 0.5) {
				nextLevel = 1;
			}
			else if (this.buf < 0) {
				this.buf = 0;
				this.playTime = (recvSTask.getNum() + 1) * CHUNKLEN;
				nextLevel = 1;
			}

			Msg.info("Played: " + this.playTime + "; Seq: " + recvSTask.getNum() + "; Server: " + curServer +  "; Level: " + curLevel + "; BW: " + bw + " kbps" + "; Buffer: " + this.buf);
			System.out.println(recvSTask.getNum() + ", " + curServer + ", "+ curLevel + ", " + bw + ", " + this.buf + ", " + this.playTime);
			seq = recvSTask.getNum() + 1;
			if (seq < 720)
				this.request(nextServer, seq, nextLevel);
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
				for (int i = 1; i < inputArgs; i ++)
				{
					String server = Host.getByName(args[i]).getName();
					this.videoServers.add(server);
					this.serverLevels.put(server, 6);
				}
			} catch (HostNotFoundException e) {
				Msg.info("Invalid deployment file: " + e.toString());
				System.exit(1);
			}
		}
		Msg.info(printMap(this.serverLevels));
		this.lCmp = new LevelComparator(this.serverLevels);
		this.comms.add(request(this.cacheAgent, 0, 1));

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
