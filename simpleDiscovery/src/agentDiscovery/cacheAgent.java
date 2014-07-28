package agentDiscovery;

import java.util.*;
import java.lang.*;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.simgrid.msg.Host;
import org.simgrid.msg.HostNotFoundException;
import org.simgrid.msg.Msg;
import org.simgrid.msg.Task;
import org.simgrid.msg.MsgException;
import org.simgrid.msg.TimeoutException;
import org.simgrid.msg.Process;
import org.simgrid.msg.Comm;

public class cacheAgent extends Process {

	private String hostName = "";
	private int pongRcv = 0;
	private ArrayList<Comm> comms;
	private Map<Integer, ArrayList<String>> cacheTbl;
	private Map<String, Integer> overlayIDs;
	private ArrayList<Integer> localContents;
	private String successor;
	private String predecessor;
	private ArrayList<Double> peerDiffs;
	private Map<String, Integer> agentsRank;
	private Map<String, Integer> agentDistances;
	private PrintWriter sendFile;
	private PrintWriter recvFile;
	private int ID;
	private int srvNum;
	private boolean isTrace;
	private static int totalVidNum = 1000;
	private static int K = 5;
	
	public cacheAgent(Host host, String name, String[] args) {
		super(host, name, args);
		this.hostName = host.getName();
		this.comms = new ArrayList<Comm>();
		this.cacheTbl = new HashMap<Integer, ArrayList<String>>();
		this.localContents = new ArrayList<Integer>();
		this.overlayIDs = new HashMap<String, Integer>();
		this.agentsRank = new HashMap<String, Integer>();
		this.agentDistances = new HashMap<String, Integer>();
	}
	
	public void genLocalContents(int totalVids, int localVids)
	{
		ArrayList<Integer> allVideos = new ArrayList<Integer>();
		for (int i = 1; i <= totalVids; i ++) {
			allVideos.add(new Integer(i));
		}

		Random rand = new Random();
		while (this.localContents.size() < localVids) 
		{
			int idx = rand.nextInt(allVideos.size());
			Integer content = allVideos.remove(idx);
			this.localContents.add(content);
			ArrayList<String> agentList = new ArrayList<String>();
			agentList.add(this.hostName);
			this.cacheTbl.put(content, agentList);
		}
	}

	public Map<Integer, ArrayList<String>> genUpdate(Map<Integer, ArrayList<String>> recvUpdate)
	{
		ArrayList<String> recvList;
		ArrayList<String> localList;
		Integer vid = 0;
		IntegerComparator bvc = new IntegerComparator(this.agentDistances);

		// Msg.info(printCacheTbl(recvUpdate));
		Map<Integer, ArrayList<String>> updates = new HashMap<Integer, ArrayList<String>>();
		Iterator recvIter = recvUpdate.entrySet().iterator();
		while (recvIter.hasNext()) {
			Map.Entry<Integer, ArrayList<String>> pair = (Map.Entry<Integer, ArrayList<String>>) recvIter.next();
			recvList = pair.getValue();
			vid = pair.getKey();
			localList = this.cacheTbl.get(vid);
			ArrayList<String> updateList = new ArrayList<String>();
			if (localList != null)
			{
				for (String agent : recvList)
				{
					if (!localList.contains(agent)) {
						if (localList.size() < K)
						{
							this.cacheTbl.get(vid).add(agent);
							updateList.add(agent);
						}
						else if (localList.size() >= K)
						{
							int distance = this.agentDistances.get(agent);
							for (String srv : localList)
							{
								int existingDistance = this.agentDistances.get(srv);
								if (existingDistance > distance)
								{
									this.cacheTbl.get(vid).remove(srv);
									this.cacheTbl.get(vid).add(agent);
									updateList.add(agent);
									break;
								}
							}
						}
					}
				}
			}
			else
			{
				updateList.addAll(recvList);
				this.cacheTbl.put(vid, updateList);
			}
			Collections.sort(this.cacheTbl.get(vid), bvc);
			Collections.sort(updateList, bvc);
			updates.put(vid, updateList);
		}
		
		return updates;
	}

	public int countSize(Map<Integer, ArrayList<String>> updates)
	{
		int updatesSize = 0;
		Iterator updateIter = updates.entrySet().iterator();
		while (updateIter.hasNext()) {
			Map.Entry<Integer, ArrayList<String>> pair = (Map.Entry<Integer, ArrayList<String>>) updateIter.next();
			updatesSize += pair.getValue().size();
		}
		return updatesSize;
	}

	public Comm forward(Map<Integer, ArrayList<String>> updates, String receiver) throws MsgException
	{
		int msgSz = countSize(updates);
		double computeDuration = (double)msgSz;
		double time = Msg.getClock();
		DiscoverTask task = new DiscoverTask("Discover", computeDuration, msgSz);
		task.setTime(time);
		task.setData(updates);

		if (isTrace)
		{
			String sendLn = String.format("%.2f", time) + ", " + Integer.toString(msgSz) + "\n";
			this.sendFile.write(sendLn);
		}

		Comm comm = task.isend(receiver);
		return comm;
	}

	public boolean loadOverlayTbl(String fileName)
	{
		int serverNum = 0;
		try {
			List<String> coords = Files.readAllLines(Paths.get(fileName), Charset.defaultCharset());
			for (String row:coords)
			{
				String[] parts = row.split(", ");

				if (parts.length != 2)
				{
					Msg.info("The overlay ID file is in wrong format: " + row);
					return false;
				}
				int curID = Integer.parseInt(parts[1].replaceAll("\\s+",""));
				// Msg.info("Load ID for " + parts[0] + ", " + parts[1]);
				this.overlayIDs.put(parts[0], curID);
				serverNum ++;
				// Msg.info("Successfully put ID" + parts[1] + " for agent " + parts[0]);
			}
			this.ID = this.overlayIDs.get(this.hostName).intValue();
		} catch (IOException e) {
			Msg.info("Invalid Overlay ID File Name: " + fileName);
			return false;
		}
		this.srvNum = serverNum;
		return true;
	}

	public void computeRank() {
		Iterator iter = this.overlayIDs.entrySet().iterator();
		while (iter.hasNext()) {
			Map.Entry<String, Integer> pair = (Map.Entry<String, Integer>) iter.next();
			int diff = getOverlayDistance(this.ID, pair.getValue().intValue());
			this.agentDistances.put(pair.getKey(), diff);
		}
		IntegerComparator bvc = new IntegerComparator(this.agentDistances);
		TreeMap<String, Integer> sortedDiffs = new TreeMap<String, Integer>(bvc);
		sortedDiffs.putAll(this.agentDistances);
		Integer rank = 1;
		for (Map.Entry<String, Integer> entry : sortedDiffs.entrySet())
		{
			this.agentsRank.put(entry.getKey(), rank);
			Msg.info(entry.getKey() + ", " + rank + ", " + entry.getValue().toString());
			rank++;
		}
	}

	public int getOverlayDistance(int curID, int remoteID)
	{
		int N = srvNum;
		int d = (curID - remoteID) % N;
		if (d < 0)
			d = d + N;
		return d;
	}

	public String printCacheTbl(Map<Integer, ArrayList<String>> tmp) {
		String line = "";
		ArrayList<String> agentList;
		Iterator iter = tmp.entrySet().iterator();
		while (iter.hasNext()) {
			Map.Entry<Integer, ArrayList<String>> pair = (Map.Entry<Integer, ArrayList<String>>) iter.next();
			line = line + pair.getKey();
			agentList = pair.getValue();
			for (String agent : agentList)
			{
				line = line + ", (" + agent + ", " + this.agentsRank.get(agent) + ", " + this.agentDistances.get(agent).toString() + ")";
			}
			line = line + "\n";
		}
		return line;
	}	

	public void parseArgs(String[] args) {
		int inputArgs = args.length;
		Msg.info("I am agent " + this.hostName);
		if (inputArgs < 3)
		{
			Msg.info("Not enough input arguments, at least two are needed: successor name, coordinates file name.");
			System.exit(1);
		}

		try {
			this.predecessor = new String(Host.getByName(args[0]).getName());
			Msg.info("Predecessor: " + this.predecessor);
			this.successor = new String(Host.getByName(args[1]).getName());
			Msg.info("Successor: " + this.successor);
		} catch (HostNotFoundException e) {
			Msg.info("Invalid successor name : " + args[1]);
			System.exit(1);
		}

		if (!loadOverlayTbl(args[2]))
		{
			Msg.info("Failed to load overlay IDs from file : " + args[2]);
			System.exit(1);
		}

		double spl_prob = 10.0 / (double) this.srvNum;
		double rnd = Math.random();
		if (rnd < spl_prob)
			this.isTrace = true;

		if (isTrace)
		{
			try {
				this.recvFile = new PrintWriter("./data/" + this.hostName + "_recv.csv");
				this.sendFile = new PrintWriter("./data/" + this.hostName + "_send.csv");
			} catch (IOException e) {
				Msg.info("Can not open trace files in data folder!");
				System.exit(1);
			}
		}
	}

	public void main(String[] args) throws MsgException {
		int localVids = 500;
		Task recvTask = null;
		int timeoutCnt = 0;
		int updateSize = 0;
		int iter = 0;

		parseArgs(args);
		genLocalContents(totalVidNum, localVids);
		computeRank();
		forward(this.cacheTbl, this.successor);
		forward(this.cacheTbl, this.predecessor);

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
					// e.printStackTrace();
				}
			}
		
			try {
				recvTask = Task.receive(this.hostName, 100);
			} catch (TimeoutException e) {
				// Msg.info("[Exception] Timeout exception in retrieving tasks!");
				timeoutCnt ++;
			}
	
			if (recvTask != null)
			{
				if (recvTask instanceof DiscoverTask)
				{
					DiscoverTask recvDTask = (DiscoverTask) recvTask;
					Map<Integer, ArrayList<String>> updates;
					Map<Integer, ArrayList<String>> rcvUpdates;
					rcvUpdates = recvDTask.getData();
					double time = Msg.getClock();
					int rcvSz = countSize(rcvUpdates);
					if (isTrace)
					{
						String rcvLn = Integer.toString(iter) + ", " + String.format("%.2f", time) + ", " + Integer.toString(rcvSz) + "\n";
						this.recvFile.write(rcvLn);
					}

					updates = genUpdate(rcvUpdates);
					iter ++;
					updateSize = countSize(updates);
					// Msg.info("Iteration : " + iter + " Update Size : " + updateSize);
					if (updateSize > 0) {
						if (recvDTask.getSenderName().equals(this.successor))
							forward(updates, this.predecessor);
						else
							forward(updates, this.successor);
					}
				}
			}

			if ((this.comms.size() == 0) && (timeoutCnt > 1000))
				break;
		}	

		// Msg.info("\n" + printCacheTbl(this.cacheTbl));
		Msg.info("goodbye!");
		if (isTrace)
		{
			this.recvFile.flush();
			this.recvFile.close();
			this.sendFile.flush();
			this.sendFile.close();
		}
	}
}
