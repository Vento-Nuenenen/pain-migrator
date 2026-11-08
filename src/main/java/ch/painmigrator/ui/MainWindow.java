package ch.painmigrator.ui;

import ch.painmigrator.convert.Converter.Result;
import ch.painmigrator.convert.Session;
import ch.painmigrator.model.Issue;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JToolBar;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.TransferHandler;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableCellRenderer;

/** Hauptfenster mit Vorschau der Zahlungen und Liste der offenen Punkte. */
public final class MainWindow extends JFrame {
    private static final long serialVersionUID = 1L;
    private static final Color REVIEW_BG = new Color(0xFFF4CC);
    private static final Color OPEN_BG = new Color(0xFBD9D3);
    private static final Color EXCLUDED_BG = new Color(0xE3E3E3);

    private final JLabel summary = new JLabel("Keine Datei geöffnet. Datei hierher ziehen oder «Öffnen» wählen.");
    private final JButton confirmAll = new JButton("Alle Adressvorschläge bestätigen");
    private final JButton save = new JButton("Speichern …");
    private final PaymentTableModel tableModel = new PaymentTableModel(this::setExcluded);
    private final DefaultListModel<Issue> issueModel = new DefaultListModel<>();
    private final JList<Issue> issueList = new JList<>(issueModel);
    private transient Session session;
    private Path file;

    public MainWindow() {
        super("PAIN-Migrator: pain.001.001.03.ch.02 nach pain.001.001.09");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setPreferredSize(new Dimension(1180, 720));

        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        JButton open = new JButton("Öffnen …");
        open.addActionListener(e -> chooseFile());
        confirmAll.addActionListener(e -> confirmAll());
        save.addActionListener(e -> save());
        bar.add(open);
        bar.addSeparator();
        bar.add(confirmAll);
        bar.addSeparator();
        bar.add(save);

        summary.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        JPanel north = new JPanel(new BorderLayout());
        north.add(bar, BorderLayout.NORTH);
        north.add(summary, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table()), issuePanel());
        split.setResizeWeight(0.62);

        add(north, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        setTransferHandler(new FileDrop());
        pack();
        setLocationRelativeTo(null);
        update();
    }

    private JTable table() {
        JTable table = new JTable(tableModel);
        table.setAutoCreateRowSorter(true);
        table.setRowHeight(table.getRowHeight() + 6);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        DefaultTableCellRenderer renderer = new DefaultTableCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus,
                    int row, int column) {
                Component c = super.getTableCellRendererComponent(t, value, selected, focus, row, column);
                setHorizontalAlignment(t.convertColumnIndexToModel(column) == PaymentTableModel.AMOUNT
                        ? SwingConstants.RIGHT : SwingConstants.LEFT);
                if (!selected) {
                    Color bg = switch (tableModel.row(t.convertRowIndexToModel(row)).status()) {
                        case OPEN -> OPEN_BG;
                        case REVIEW -> REVIEW_BG;
                        case EXCLUDED -> EXCLUDED_BG;
                        case OK -> t.getBackground();
                    };
                    c.setBackground(bg);
                    c.setForeground(bg == t.getBackground() ? t.getForeground() : Color.BLACK);
                }
                return c;
            }
        };
        table.setDefaultRenderer(String.class, renderer);
        int[] widths = {70, 120, 70, 170, 230, 190, 95, 60, 90, 200};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        return table;
    }

    private JPanel issuePanel() {
        issueList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        issueList.setCellRenderer(new DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
                    boolean focus) {
                Issue i = (Issue) value;
                String mark = i.severity() == Issue.Severity.INFO ? "Info"
                        : i.resolved() ? "Erledigt"
                        : i.severity() == Issue.Severity.OPEN ? "Eingabe nötig"
                        : i.severity() == Issue.Severity.CONFIRM ? "Bestätigung nötig" : "Vorschlag prüfen";
                int n = i.payments().size();
                String text = "[" + mark + "]  " + i.title() + (n > 1 ? "  (" + n + " Zahlungen)" : "");
                Component c = super.getListCellRendererComponent(list, text, index, selected, focus);
                if (!i.resolved()) {
                    c.setFont(c.getFont().deriveFont(Font.BOLD));
                }
                return c;
            }
        });
        issueList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    editSelected();
                }
            }
        });
        JButton edit = new JButton("Bearbeiten …");
        edit.addActionListener(e -> editSelected());
        // Ohne gewählte Zeile gibt es nichts zu bearbeiten, also ist der Button gesperrt.
        edit.setEnabled(false);
        issueList.addListSelectionListener(e -> edit.setEnabled(issueList.getSelectedValue() != null));
        JPanel head = new JPanel(new BorderLayout());
        head.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        head.add(new JLabel("Rückfragen und Hinweise (Doppelklick zum Bearbeiten)"), BorderLayout.WEST);
        head.add(edit, BorderLayout.EAST);
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(head, BorderLayout.NORTH);
        panel.add(new JScrollPane(issueList), BorderLayout.CENTER);
        return panel;
    }

    private void chooseFile() {
        JFileChooser chooser = new JFileChooser(file == null ? null : file.toFile().getParentFile());
        chooser.setFileFilter(new FileNameExtensionFilter("XML-Dateien", "xml"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            open(chooser.getSelectedFile().toPath());
        }
    }

    /** Öffnet eine Datei im Hintergrund. */
    public void open(Path path) {
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new SwingWorker<Session, Void>() {
            @Override
            protected Session doInBackground() throws Exception {
                return Session.open(path);
            }

            @Override
            protected void done() {
                setCursor(Cursor.getDefaultCursor());
                try {
                    session = get();
                    file = path;
                    update();
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable cause = e.getCause();
                    String message = cause instanceof Session.RejectedFile ? cause.getMessage()
                            : "Die Datei konnte nicht gelesen werden:\n" + cause.getMessage();
                    JOptionPane.showMessageDialog(MainWindow.this, message, "Datei nicht verarbeitbar",
                            JOptionPane.ERROR_MESSAGE);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }.execute();
    }

    private void setExcluded(String id, boolean excluded) {
        session.setExcluded(id, excluded);
        update();
    }

    private void confirmAll() {
        int n = session.confirmAllProposals();
        update();
        JOptionPane.showMessageDialog(this, n + " Adressvorschläge bestätigt.", "Vorschläge", JOptionPane.INFORMATION_MESSAGE);
    }

    private void editSelected() {
        Issue issue = issueList.getSelectedValue();
        if (issue == null) {
            return;
        }
        if (issue.severity() == Issue.Severity.INFO) {
            JOptionPane.showMessageDialog(this, issue.detail(), issue.title(), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (IssueDialog.show(this, session, issue)) {
            update();
        }
    }

    private void update() {
        confirmAll.setEnabled(false);
        save.setEnabled(false);
        issueModel.clear();
        if (session == null) {
            return;
        }
        Result r = session.result();
        tableModel.setRows(r.payments());
        List<Issue> sorted = new ArrayList<>(r.issues());
        sorted.sort(Comparator.comparingInt(MainWindow::rank));
        issueModel.addAll(sorted);
        confirmAll.setEnabled(r.issues().stream()
                .anyMatch(i -> i.severity() == Issue.Severity.REVIEW && !i.resolved()));
        save.setEnabled(r.ready());

        StringBuilder sb = new StringBuilder("<html><b>").append(escape(file.getFileName().toString())).append("</b>")
                .append("&nbsp;&nbsp;MsgId ").append(escape(r.msgId()))
                .append("&nbsp;&nbsp;|&nbsp;&nbsp;").append(r.activeCount()).append(" Zahlungen");
        for (Map.Entry<String, BigDecimal> e : r.sums().entrySet()) {
            sb.append("&nbsp;&nbsp;").append(e.getKey()).append(' ').append(PaymentTableModel.money(e.getValue()));
        }
        if (r.excludedCount() > 0) {
            sb.append("&nbsp;&nbsp;|&nbsp;&nbsp;").append(r.excludedCount()).append(" ausgeschlossen");
        }
        sb.append("&nbsp;&nbsp;|&nbsp;&nbsp;");
        sb.append(r.openCount() == 0 ? "Alles geklärt" : r.openCount() + " Punkte offen").append("</html>");
        summary.setText(sb.toString());
    }

    private static int rank(Issue i) {
        if (i.severity() == Issue.Severity.INFO) {
            return 3;
        }
        if (i.resolved()) {
            return 2;
        }
        return i.severity() == Issue.Severity.OPEN ? 0 : 1;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;");
    }

    private void save() {
        Session.Export export = session.export();
        if (!export.ok()) {
            JTextArea area = new JTextArea(String.join("\n", export.errors()), 14, 70);
            area.setEditable(false);
            JOptionPane.showMessageDialog(this, new JScrollPane(area),
                    "Schlussprüfung fehlgeschlagen, es wurde nichts gespeichert", JOptionPane.ERROR_MESSAGE);
            return;
        }
        String name = file.getFileName().toString().replaceFirst("(?i)\\.xml$", "") + ".pain.001.001.09.xml";
        JFileChooser chooser = new JFileChooser(file.toFile().getParentFile());
        chooser.setSelectedFile(new File(file.toFile().getParentFile(), name));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path target = chooser.getSelectedFile().toPath();
        if (Files.exists(target) && JOptionPane.showConfirmDialog(this, target.getFileName() + " überschreiben?",
                "Datei existiert", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            // Erst vollständig schreiben, dann umbenennen: so entsteht nie eine halbe Zahlungsdatei.
            Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.write(temp, export.xml());
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Speichern fehlgeschlagen:\n" + e.getMessage(), "Fehler",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }
        Result r = session.result();
        StringBuilder done = new StringBuilder("Gespeichert: ").append(target).append("\n\n")
                .append(r.activeCount()).append(" Zahlungen, Prüfung gegen pain.001.001.09.ch.03 bestanden.");
        if (r.excludedCount() > 0) {
            done.append("\n\nAchtung: ").append(r.excludedCount()).append(" Zahlungen wurden ausgeschlossen und sind "
                    + "nicht in der Datei. Sie müssen separat erfasst werden.");
        }
        done.append("\n\nDie MsgId wurde beibehalten. Wurde die alte Datei bereits eingereicht, lehnt die Bank die neue "
                + "als Duplikat ab.");
        JOptionPane.showMessageDialog(this, done.toString(), "Gespeichert", JOptionPane.INFORMATION_MESSAGE);
    }

    /** Nimmt Dateien per Drag &amp; Drop entgegen. */
    private final class FileDrop extends TransferHandler {
        private static final long serialVersionUID = 1L;

        @Override
        public boolean canImport(TransferSupport support) {
            return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
        }

        @Override
        public boolean importData(TransferSupport support) {
            try {
                List<?> files = (List<?>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                if (files.isEmpty()) {
                    return false;
                }
                open(((File) files.get(0)).toPath());
                return true;
            } catch (java.awt.datatransfer.UnsupportedFlavorException | IOException e) {
                return false;
            }
        }
    }
}
