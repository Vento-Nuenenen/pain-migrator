package ch.painmigrator.ui;

import ch.painmigrator.model.PaymentView;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.function.BiConsumer;
import javax.swing.table.AbstractTableModel;

/** Tabelle der Zahlungsvorschau. */
final class PaymentTableModel extends AbstractTableModel {
    private static final long serialVersionUID = 1L;
    private static final String[] COLUMNS = {"Aufnehmen", "Status", "Art", "Empfänger", "Adresse", "Konto", "Betrag",
        "Währung", "Ausführung", "Referenz"};
    static final int INCLUDE = 0;
    static final int AMOUNT = 6;

    private final transient BiConsumer<String, Boolean> onExclude;
    private transient List<PaymentView> rows = List.of();

    PaymentTableModel(BiConsumer<String, Boolean> onExclude) {
        this.onExclude = onExclude;
    }

    void setRows(List<PaymentView> rows) {
        this.rows = rows;
        fireTableDataChanged();
    }

    PaymentView row(int index) {
        return rows.get(index);
    }

    static String statusText(PaymentView.Status status) {
        return switch (status) {
            case OK -> "Bereit";
            case REVIEW -> "Vorschlag prüfen";
            case OPEN -> "Eingabe nötig";
            case EXCLUDED -> "Ausgeschlossen";
        };
    }

    static String money(BigDecimal amount) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols();
        symbols.setDecimalSeparator('.');
        symbols.setGroupingSeparator('\'');
        return new DecimalFormat("#,##0.00###", symbols).format(amount);
    }

    @Override
    public int getRowCount() {
        return rows.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMNS[column];
    }

    @Override
    public Class<?> getColumnClass(int column) {
        return column == INCLUDE ? Boolean.class : String.class;
    }

    @Override
    public boolean isCellEditable(int row, int column) {
        return column == INCLUDE;
    }

    @Override
    public void setValueAt(Object value, int row, int column) {
        if (column == INCLUDE) {
            onExclude.accept(rows.get(row).id(), !Boolean.TRUE.equals(value));
        }
    }

    private static String orDash(String s) {
        return s == null || s.isBlank() ? "–" : s;
    }

    @Override
    public Object getValueAt(int row, int column) {
        PaymentView p = rows.get(row);
        return switch (column) {
            case INCLUDE -> p.status() != PaymentView.Status.EXCLUDED;
            case 1 -> statusText(p.status());
            case 2 -> p.oldType() + " → " + p.newType();
            case 3 -> orDash(p.creditor());
            case 4 -> orDash(p.creditorAddress());
            case 5 -> orDash(p.account());
            case AMOUNT -> money(p.amount());
            case 7 -> p.currency();
            case 8 -> orDash(p.executionDate());
            default -> orDash(p.reference());
        };
    }
}
