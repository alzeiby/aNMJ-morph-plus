package io.github.alzeiby.anmjmorphplus;

import org.scijava.command.Command;
import org.scijava.plugin.Plugin;

@Plugin(type = Command.class, menuPath = "Analyze>Tools>aNMJ-morph+")
public class ANMJMorphCommand implements Command {

    private final Runnable workflowRunner;

    public ANMJMorphCommand() {
        this(new InputWorkflowRunner());
    }

    ANMJMorphCommand(final Runnable workflowRunner) {
        this.workflowRunner = workflowRunner;
    }

    @Override
    public void run() {
        workflowRunner.run();
    }
}
