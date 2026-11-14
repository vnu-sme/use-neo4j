package org.uet.dse.ocl2cypher.plugin.ui;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;

/** Shared palette and widget construction. */
final class WorkbenchStyle {
    private WorkbenchStyle() { }

    static final Color WINDOW = new Color(24, 25, 30);
    static final Color SURFACE = new Color(38, 40, 47);
    static final Color SURFACE_2 = new Color(31, 33, 39);
    static final Color EDITOR = new Color(27, 29, 33);
    static final Color BORDER = new Color(57, 60, 69);
    static final Color TEXT = new Color(230, 232, 237);
    static final Color MUTED = new Color(151, 155, 166);
    static final Color ACCENT = new Color(59, 139, 199);
    static final Color OK = new Color(83, 181, 112);
    static final Color ERROR_COLOR = new Color(224, 91, 91);
    static final Border FIELD_BORDER = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER), BorderFactory.createEmptyBorder(4, 7, 4, 7));

    static JPanel card(String title, JComponent tools, JComponent content) {
        JPanel card = panel(new BorderLayout(), SURFACE); card.setBorder(BorderFactory.createLineBorder(BORDER));
        JPanel head = panel(new BorderLayout(), SURFACE); head.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 5));
        JLabel label = new JLabel(title); label.setForeground(TEXT); label.setFont(label.getFont().deriveFont(13f)); head.add(label, BorderLayout.WEST); if (tools != null) head.add(tools, BorderLayout.EAST);
        JPanel body = panel(new BorderLayout(), SURFACE); body.setBorder(BorderFactory.createEmptyBorder(0, 7, 7, 7)); body.add(content);
        card.add(head, BorderLayout.NORTH); card.add(body); return card;
    }

    static JPanel panel(LayoutManager layout, Color color) { JPanel p = new JPanel(layout); p.setBackground(color); p.setForeground(TEXT); return p; }

    static JTextField field(String value) { JTextField f = new JTextField(value); styleField(f); return f; }

    static JPasswordField passwordField() { JPasswordField f = new JPasswordField(); styleField(f); return f; }

    private static void styleField(JTextField f) { f.setBackground(EDITOR); f.setForeground(TEXT); f.setCaretColor(TEXT); f.setSelectionColor(ACCENT); f.setBorder(FIELD_BORDER); }

    static JTextArea area(boolean editable, Color color) { JTextArea a = new JTextArea(); a.setEditable(editable); a.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12)); a.setBackground(EDITOR); a.setForeground(color); a.setCaretColor(TEXT); a.setSelectionColor(ACCENT); a.setBorder(BorderFactory.createEmptyBorder(6, 7, 6, 7)); return a; }

    static JButton button(String text, boolean primary) { JButton b = new JButton(text); b.setFocusPainted(false); b.setForeground(TEXT); b.setBackground(primary ? ACCENT : new Color(57, 59, 67)); b.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(primary ? ACCENT.brighter() : BORDER), BorderFactory.createEmptyBorder(5, 10, 5, 10))); return b; }

    static JButton tiny(String text, boolean primary) { JButton b = button(text, primary); b.setFont(b.getFont().deriveFont(10f)); b.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BORDER), BorderFactory.createEmptyBorder(2, 5, 2, 5))); return b; }

    static JButton icon(String text, String tooltip) { JButton b = button(text, false); b.setToolTipText(tooltip); b.setPreferredSize(new Dimension(27, 25)); b.setBorder(BorderFactory.createLineBorder(BORDER)); return b; }

    static JButton nav(String text, boolean active) { JButton b = new JButton(text); b.setFocusPainted(false); b.setContentAreaFilled(false); b.setForeground(active ? TEXT : MUTED); b.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, active ? 2 : 0, 0, ACCENT), BorderFactory.createEmptyBorder(10, 16, active ? 8 : 10, 16))); return b; }

    static JLabel smallLabel(String text) { JLabel l = new JLabel(text); l.setForeground(MUTED); l.setFont(l.getFont().deriveFont(11f)); return l; }

    static JPanel labelled(String label, JComponent field) { JPanel p = panel(new BorderLayout(0, 3), SURFACE); p.add(smallLabel(label), BorderLayout.NORTH); p.add(field); return p; }

    static GridBagConstraints formGbc() { GridBagConstraints g = new GridBagConstraints(); g.fill = GridBagConstraints.HORIZONTAL; g.anchor = GridBagConstraints.WEST; g.insets = new Insets(3, 0, 3, 5); return g; }

    static void addRow(JPanel p, GridBagConstraints g, int row, String label, JComponent field, JComponent extra) { g.gridy = row; g.gridwidth = 1; g.gridx = 0; g.weightx = 0; p.add(smallLabel(label), g); g.gridx = 1; g.weightx = 1; p.add(field, g); g.gridx = 2; g.weightx = 0; p.add(extra, g); }

    static JScrollPane scroll(Component content) { JScrollPane p = new JScrollPane(content); p.setBorder(BorderFactory.createLineBorder(BORDER)); p.getViewport().setBackground(EDITOR); p.getVerticalScrollBar().setUnitIncrement(14); return p; }

    static JSplitPane split(int orientation, Component a, Component b, double weight) { JSplitPane p = new JSplitPane(orientation, a, b); p.setResizeWeight(weight); p.setDividerSize(6); p.setContinuousLayout(true); p.setBorder(null); p.setBackground(WINDOW); return p; }

    static void styleTable(JTable t) { t.setBackground(EDITOR); t.setForeground(TEXT); t.setSelectionBackground(new Color(48, 83, 110)); t.setGridColor(BORDER); t.setRowHeight(24); t.setShowVerticalLines(false); t.getTableHeader().setBackground(SURFACE_2); t.getTableHeader().setForeground(TEXT); }
}
