package masterworker;
import org.simgrid.msg.*;

public class BasicTask extends org.simgrid.msg.Task {
	public BasicTask(String name, double computeDuration, double messageSize) {
		super(name, computeDuration, messageSize);
	} 
}
