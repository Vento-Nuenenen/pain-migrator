package ch.painmigrator;

import ch.painmigrator.ui.MainWindow;
import java.awt.GraphicsEnvironment;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** Startpunkt des Programms. */
public final class App {
    private App() {
    }

    public static void main(String[] args) {
        Thread.setDefaultUncaughtExceptionHandler(App::showFailure);
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (ReflectiveOperationException | javax.swing.UnsupportedLookAndFeelException e) {
                // Das Standard-Aussehen funktioniert ebenfalls.
            }
            MainWindow window = new MainWindow();
            window.setVisible(true);
            if (args.length > 0) {
                window.open(java.nio.file.Path.of(args[0]));
            }
        });
    }

    /** Beim Start per Doppelklick gibt es keine Konsole, ein Fehler muss deshalb im Fenster erscheinen. */
    private static void showFailure(Thread thread, Throwable failure) {
        failure.printStackTrace();
        if (!GraphicsEnvironment.isHeadless()) {
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
                    "Unerwarteter Fehler. Die letzte Aktion wurde nicht abgeschlossen.\n" + failure, "Fehler",
                    JOptionPane.ERROR_MESSAGE));
        }
    }
}
