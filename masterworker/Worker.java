package masterworker;
import org.simgrid.msg.*;

public class Worker extends org.simgrid.msg.Process {
	public Worker(Host host, String name, String[]args) { 
		// Mandatory: this constructor is
		super(host,name,args); // used internally
	}

	public void main(String[ ] args) throws TransferFailureException, HostFailureException, TimeoutException, TaskCancelledException {
		String id = args[0];

		while (true) {
				Task t = Task.receive("worker-" + id);

				if (t instanceof FinalizeTask)
					break;

				BasicTask task = (BasicTask)t;
				Msg.info("Processing ’" + task.getName() + "’");
				task.execute();
				Msg.info("’" + task.getName() + "’ done ");
			}
		
		Msg.info("Received Finalize. I’m done. See you!");
	} 
}
