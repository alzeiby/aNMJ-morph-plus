package io.github.alzeiby.anmjmorphplus;

import org.scijava.command.Command;
import org.scijava.plugin.Plugin;

@Plugin(type = Command.class, menuPath = "Analyze>Tools>aNMJ-morph+")
public class ANMJMorphCommand implements Command {
    @Override
    public void run() {
        new InputWorkflowRunner().run();
    }
}
