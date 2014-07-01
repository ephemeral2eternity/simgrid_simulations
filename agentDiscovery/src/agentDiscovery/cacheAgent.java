package agentDiscovery;

import java.util.*;
import java.lang.*;
import java.io.IOException;
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
	private Map<String, Double[]> vivaldiTbl;
	private ArrayList<Integer> localContents;
	private String successor;
	private ArrayList<Double> peerDiffs;
	private Map<String, Integer> agentsRank;
	private Map<String, Double> agentDistances;
	private double[] vivaldiCoords = {0.0, 0.0};
	private static int totalVidNum = 15;
	
	public cacheAgent(Host host, String name, String[] args) {
		super(host, name, args);
		this.hostName = host.getName();
		this.comms = new ArrayList<Comm>();
		this.cacheTbl = new HashMap<Integer, ArrayList<String>>();
		this.localContents = new ArrayList<Integer>();
		this.vivaldiTbl = new HashMap<String, Double[]>();
		this.agentsRank = new HashMap<String, Integer>();
		this.agentDistances = new HashMap<String, Double>();
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
		ValueComparator bvc = new ValueComparator(this.agentDistances);

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
					if (!localList.contains(agent))
					{
						this.cacheTbl.get(vid).add(agent);
						updateList.add(agent);
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

	public Comm forward(Map<Integer, ArrayList<String>> updates) throws MsgException
	{
		double msgSz = countSize(updates);
		double computeDuration = msgSz * 0.01;
		double time = Msg.getClock();
		DiscoverTask task = new DiscoverTask("Discover", computeDuration, msgSz);
		task.setTime(time);
		task.setData(updates);
		Comm comm = task.isend(successor);
		return comm;
	}

        void copyArray(double[] dst, double[] src) {
		int i, len;
		len = src.length;
		if (len != dst.length)
		{
			System.out.println("[copyArray Error] The dimension of two input arrays are not the same!");
		}
		else
		{
			for (i = 0; i < len; i ++)
			{
				dst[i] = src[i];
			}
		}
	}

	public boolean loadVivaldiTbl(String fileName)
	{
		try {
			List<String> coords = Files.readAllLines(Paths.get(fileName), Charset.defaultCharset());
			for (String row:coords)
			{
				String[] parts = row.split(",");

				if (parts.length != 3)
				{
					Msg.info("The coordinates file is in wrong format: " + row);
					return false;
				}
				Double[] curCoords = {0.0, 0.0};
				curCoords[0] = Double.parseDouble(parts[1]);
				curCoords[1] = Double.parseDouble(parts[2]);
				this.vivaldiTbl.put(parts[0], curCoords);
			}
		} catch (IOException e) {
			Msg.info("Invalid Vivaldi Coordinates File Name: " + fileName);
			return false;
		}

		return true;
	}

	public void computeRank() {
		copyArray(this.vivaldiCoords, convertDouble(this.vivaldiTbl.get(this.hostName)));
		Iterator iter = this.vivaldiTbl.entrySet().iterator();
		while (iter.hasNext()) {
			Map.Entry<String, Double[]> pair = (Map.Entry<String, Double[]>) iter.next();
			double diff = VivaldiTask.getDistance(this.vivaldiCoords, convertDouble(pair.getValue()));
			this.agentDistances.put(pair.getKey(), diff);
		}
		ValueComparator bvc = new ValueComparator(this.agentDistances);
		TreeMap<String, Double> sortedDiffs = new TreeMap<String, Double>(bvc);
		sortedDiffs.putAll(this.agentDistances);
		Integer rank = 1;
		for (Map.Entry<String, Double> entry : sortedDiffs.entrySet())
		{
			this.agentsRank.put(entry.getKey(), rank);
			Msg.info(entry.getKey() + ", " + rank + ", " + entry.getValue().toString());
			rank++;
		}
	}

	public double[] convertDouble(Double[] input)
	{
		double[] output = {0.0, 0.0};
		output[0] = (double) input[0];
		output[1] = (double) input[1];
		return output;
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

	public String printVivaldiTbl(Map<String, Double[]> coords) {
		String line = "";
		Double[] xy = {0.0, 0.0};
		Iterator iter = coords.entrySet().iterator();
		while (iter.hasNext()) {
			Map.Entry<String, Double[]> pair = (Map.Entry<String, Double[]>) iter.next();
			line = line + pair.getKey() + ", ";
			xy = pair.getValue();
			line = line + xy[0].toString() + ", " + xy[1].toString();
			line = line + "\n";
		}
		return line;
	}

	public void main(String[] args) throws MsgException {
		int inputArgs = args.length;
		int localVids = 10;
		Task recvTask = null;
		int timeoutCnt = 0;
		int updateSize = 0;
		int iter = 0;

		Msg.info("I am agent " + this.hostName);
		if (inputArgs < 2)
		{
			Msg.info("Not enough input arguments, at least two are needed: successor name, coordinates file name.");
			System.exit(1);
		}

		try {
			this.successor = new String(Host.getByName(args[0]).getName());
			Msg.info("Successor: " + this.successor);
		} catch (HostNotFoundException e) {
			Msg.info("Invalid successor name : " + args[0]);
			System.exit(1);
		}

		if (!loadVivaldiTbl(args[1]))
		{
			Msg.info("Failed to load vivaldi coordinates from file : " + args[1]);
			System.exit(1);
		}

		if (inputArgs > 2)
		{
			localVids = Integer.parseInt(args[2]);
		}

		genLocalContents(totalVidNum, localVids);
		computeRank();
		forward(this.cacheTbl);

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
					updates = genUpdate(rcvUpdates);
					iter ++;
					updateSize = countSize(updates);
					Msg.info("Iteration : " + iter + " Update Size : " + updateSize);
					if (updateSize > 0)
						forward(updates);
				}
			}

			if ((this.comms.size() == 0) && ((iter > 100) || (updateSize == 0)) && (timeoutCnt > 10))
				break;
		}	

		// System.out.println(this.hostName + ", " + curCoords[0] + " ," + curCoords[1]);
		Msg.info("\n" + printCacheTbl(this.cacheTbl));
		Msg.info("goodbye!");
	}
}
