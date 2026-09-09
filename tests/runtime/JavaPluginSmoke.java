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
            require(command != null, "aNMJ-morph+ command was not discovered");
            require(COMMAND_CLASS.equals(command.getClassName()), "Unexpected command class");
            require(
                command.getMenuPath() != null && MENU_PATH.equals(command.getMenuPath().getMenuString()),
                "Unexpected menu path"
            );
            final Class<?> commandClass = command.loadClass();
            require(
                commandClass.getResource("/legacy/aNMJ-morph macro.txt") == null,
                "Legacy macro resource is still packaged in the plugin"
            );
            try {
                Class.forName("io.github.alzeiby.anmjmorphplus.LegacyMacroRunner");
                throw new IllegalStateException("LegacyMacroRunner is still packaged in the plugin");
            } catch (ClassNotFoundException expected) {
                // Expected after macro retirement.
            }
            System.out.println("DONE java plugin smoke");
        }
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
