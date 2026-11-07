package ch.painmigrator;

import ch.painmigrator.ui.MainWindow;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** Startpunkt des Programms. */
public final class App {
    private App() {
    }

    public static void main(String[] args) {
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
}
