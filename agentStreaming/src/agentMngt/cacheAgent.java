package agentMngt;

import java.util.ArrayList;
import java.lang.reflect.Array;
import java.util.Map;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Random;
import org.simgrid.msg.Host;
import org.simgrid.msg.HostNotFoundException;
import org.simgrid.msg.Msg;
import org.simgrid.msg.Task;
import org.simgrid.msg.MsgException;
import org.simgrid.msg.TimeoutException;
import org.simgrid.msg.Process;
import org.simgrid.msg.Comm;

public class cacheAgent extends Process {

	private ArrayList<Comm> comms;
	private String hostName;
	private double[] curCoords = {0.0, 0.0};
	static private double[] bitrates = {400.0, 628.0, 986.0, 1549.0, 2433.0, 3821.0, 6000.0};
	static private double CHUNKLEN = 5.0;
	
	public cacheAgent(Host host, String name, String[] args) {
		super(host, name, args);
		this.hostName = host.getName();
		this.comms = new ArrayList<Comm>();
	}

	public Comm processRequest(Task request) throws MsgException
	{
		double msgSz = 0;
		double computeDuration = 0;
		StreamingTask recvRequest = (StreamingTask) request;
		int rcvLevel = recvRequest.getLevel();
		msgSz = bitrates[rcvLevel - 1] * 1024 * 5 / 8;
		StreamingTask data = new StreamingTask("Data", computeDuration, msgSz);
		data.setTime(recvRequest.getTime());
		data.setLevel(rcvLevel);
		data.setChunkLen(CHUNKLEN);
		data.setNum(recvRequest.getNum());
		data.setIsRequest(false);
		Comm comm = data.isend(recvRequest.getSenderName());
		return comm;
	}

	public void main(String[] args) throws MsgException {
		Task recvTask = null;
		int lvls = this.bitrates.length;
		int timeoutCnt = 0;
		
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
					// this.comms.remove(i);
				}
			}
		
			try {
				recvTask = Task.receive(this.hostName, 1000);
			} catch (TimeoutException e) {
				// Msg.info("[Exception] Timeout exception in retrieving tasks!");
				timeoutCnt ++;
			}
	
			if (recvTask != null)
			{
				timeoutCnt = 0;
				if (recvTask instanceof StreamingTask)
				{
					this.comms.add(processRequest(recvTask));
				}
			}

			// boolean converge = isConverge(this.peerDiffs, th);		
			if ((this.comms.size() == 0) && (timeoutCnt > 100))
				break;
		}	

		Msg.info("goodbye!");
	}
}
