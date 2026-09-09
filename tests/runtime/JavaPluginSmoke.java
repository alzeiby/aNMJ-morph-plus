import org.scijava.Context;
import org.scijava.command.CommandInfo;
import org.scijava.command.CommandService;
import org.scijava.plugin.PluginService;

public final class JavaPluginSmoke {

    private static final String COMMAND_CLASS =
        "io.github.alzeiby.anmjmorphplus.ANMJMorphCommand";
    private static final String MENU_PATH = "Analyze > Tools > aNMJ-morph+";

    private JavaPluginSmoke() {
    }

    public static void main(final String[] args) throws Exception {
        try (Context context = new Context(PluginService.class, CommandService.class)) {
            final PluginService plugins = context.service(PluginService.class);
            plugins.reloadPlugins();

            final CommandService commands = context.service(CommandService.class);
            final CommandInfo command = commands.getCommand(COMMAND_CLASS);
            if (command == null) {
                throw new IllegalStateException("aNMJ-morph+ command was not discovered");
            }
            if (!COMMAND_CLASS.equals(command.getClassName())) {
                throw new IllegalStateException("Unexpected command class: " + command.getClassName());
            }
            if (command.getMenuPath() == null ||
                !MENU_PATH.equals(command.getMenuPath().getMenuString())) {
                throw new IllegalStateException(
                    "Unexpected menu path: " +
                    (command.getMenuPath() == null ? "<none>" : command.getMenuPath().getMenuString())
                );
            }

            command.loadClass();
            System.out.println("DONE java plugin smoke");
        }
    }
}
