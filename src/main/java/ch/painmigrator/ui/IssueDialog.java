package ch.painmigrator.ui;

import ch.painmigrator.convert.Session;
import ch.painmigrator.model.Decision;
import ch.painmigrator.model.Field;
import ch.painmigrator.model.Issue;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.WindowConstants;

/** Rückfrage zu einem Punkt: Eingabe, Vorschlag bestätigen, Alternative oder Ausschluss. */
final class IssueDialog extends JDialog {
    private static final long serialVersionUID = 1L;

    private final transient Session session;
    private final transient Issue issue;
    private final Map<String, JTextField> inputs = new LinkedHashMap<>();
    private final JLabel error = new JLabel(" ");
    private boolean changed;

    private IssueDialog(Frame owner, Session session, Issue issue) {
        super(owner, issue.title(), true);
        this.session = session;
        this.issue = issue;
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        content.add(description(), BorderLayout.NORTH);
        if (!issue.fields().isEmpty()) {
            content.add(form(), BorderLayout.CENTER);
        }
        JPanel south = new JPanel(new BorderLayout());
        error.setForeground(new Color(0xB00020));
        south.add(error, BorderLayout.NORTH);
        south.add(buttons(), BorderLayout.SOUTH);
        content.add(south, BorderLayout.SOUTH);
        setContentPane(content);
        pack();
        setMinimumSize(getSize());
        setLocationRelativeTo(owner);
    }

    /** Zeigt den Dialog und meldet, ob sich eine Entscheidung geändert hat. */
    static boolean show(Frame owner, Session session, Issue issue) {
        IssueDialog dialog = new IssueDialog(owner, session, issue);
        dialog.setVisible(true);
        return dialog.changed;
    }

    private JTextArea description() {
        StringBuilder text = new StringBuilder(issue.detail());
        int affected = issue.payments().size();
        if (affected > 0) {
            text.append("\n\nBetrifft ").append(affected).append(affected == 1 ? " Zahlung: " : " Zahlungen: ")
                    .append(String.join(", ", issue.payments().subList(0, Math.min(affected, 12))));
            if (affected > 12) {
                text.append(" …");
            }
        }
        JTextArea area = new JTextArea(text.toString());
        area.setEditable(false);
        area.setOpaque(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setColumns(52);
        return area;
    }

    private JPanel form() {
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 0, 3, 8);
        c.anchor = GridBagConstraints.WEST;
        Decision previous = issue.decision();
        int row = 0;
        for (Field f : issue.fields()) {
            String text = previous != null && previous.values().containsKey(f.name())
                    ? previous.values().get(f.name()) : f.value();
            JTextField input = new JTextField(text, 34);
            inputs.put(f.name(), input);
            c.gridx = 0;
            c.gridy = row;
            c.weightx = 0;
            c.fill = GridBagConstraints.NONE;
            form.add(new JLabel(f.label() + (f.required() ? " *" : "")), c);
            c.gridx = 1;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            form.add(input, c);
            row++;
        }
        return form;
    }

    private JPanel buttons() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        if (issue.decision() != null) {
            JButton undo = new JButton("Entscheidung zurücknehmen");
            undo.addActionListener(e -> {
                session.undo(issue.key());
                finish();
            });
            panel.add(undo);
        }
        if (issue.excludable()) {
            JButton exclude = new JButton(issue.payments().size() > 1 ? "Zahlungen ausschliessen" : "Zahlung ausschliessen");
            exclude.addActionListener(e -> {
                session.decide(issue.key(), Decision.exclude());
                finish();
            });
            panel.add(exclude);
        }
        if (issue.omitLabel() != null) {
            JButton omit = new JButton(issue.omitLabel());
            omit.addActionListener(e -> omit());
            panel.add(omit);
        }
        JButton cancel = new JButton("Abbrechen");
        cancel.addActionListener(e -> dispose());
        panel.add(cancel);
        JButton apply = new JButton(issue.fields().isEmpty() ? "Bestätigen" : "Übernehmen");
        apply.addActionListener(e -> apply());
        panel.add(apply);
        getRootPane().setDefaultButton(apply);
        return panel;
    }

    private void omit() {
        // Ein fehlender Name bleibt auch ohne Adresse Pflicht, deshalb wird er mitgegeben.
        Field name = issue.fields().stream().filter(f -> "Nm".equals(f.name())).findFirst().orElse(null);
        if (name != null) {
            String problem = name.validate(inputs.get("Nm").getText());
            if (problem != null) {
                error.setText(problem);
                return;
            }
            Map<String, String> values = new HashMap<>();
            values.put("Nm", inputs.get("Nm").getText().trim());
            session.decide(issue.key(), new Decision(Decision.Action.OMIT, Map.copyOf(values)));
        } else {
            session.decide(issue.key(), Decision.omit());
        }
        finish();
    }

    private void apply() {
        Map<String, String> values = new HashMap<>();
        for (Field f : issue.fields()) {
            String text = inputs.get(f.name()).getText();
            String problem = f.validate(text);
            if (problem != null) {
                error.setText(problem);
                inputs.get(f.name()).requestFocusInWindow();
                return;
            }
            values.put(f.name(), text.trim());
        }
        session.decide(issue.key(), Decision.apply(values));
        finish();
    }

    private void finish() {
        changed = true;
        dispose();
    }
}
