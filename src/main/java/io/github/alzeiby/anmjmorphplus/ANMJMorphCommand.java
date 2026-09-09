package io.github.alzeiby.anmjmorphplus;

import org.scijava.command.Command;
import org.scijava.plugin.Plugin;

@Plugin(type = Command.class, menuPath = "Analyze>Tools>aNMJ-morph+")
public class ANMJMorphCommand implements Command {

    private final WorkflowRunner workflowRunner;

    public ANMJMorphCommand() {
        this(new InputWorkflowRunner());
    }

    ANMJMorphCommand(final WorkflowRunner workflowRunner) {
        this.workflowRunner = workflowRunner;
    }

    @Override
    public void run() {
        workflowRunner.run();
    }
}
