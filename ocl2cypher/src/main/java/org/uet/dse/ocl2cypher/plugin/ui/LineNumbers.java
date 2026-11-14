package org.uet.dse.ocl2cypher.plugin.ui;

import javax.swing.*;
import javax.swing.event.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.*;
import static org.uet.dse.ocl2cypher.plugin.ui.WorkbenchStyle.*;

final class LineNumbers extends JComponent {
    private final JTextArea text;
    LineNumbers(JTextArea text) { this.text = text; setFont(text.getFont()); setForeground(MUTED); setBackground(SURFACE_2); setOpaque(true); setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6)); text.getDocument().addDocumentListener(new DocumentListener() { public void insertUpdate(DocumentEvent e) { refresh(); } public void removeUpdate(DocumentEvent e) { refresh(); } public void changedUpdate(DocumentEvent e) { refresh(); } private void refresh() { revalidate(); repaint(); } }); }
    @Override public Dimension getPreferredSize() { FontMetrics f = getFontMetrics(getFont()); return new Dimension(12 + Math.max(2, String.valueOf(text.getLineCount()).length()) * f.charWidth('0'), text.getPreferredSize().height); }
    @Override protected void paintComponent(Graphics graphics) { super.paintComponent(graphics); Graphics2D g = (Graphics2D) graphics.create(); g.setColor(getForeground()); g.setFont(getFont()); FontMetrics f = g.getFontMetrics(); int y = 6 + f.getAscent(); for (int i = 0; i < text.getLineCount(); i++) { String n = String.valueOf(i + 1); g.drawString(n, getWidth() - 6 - f.stringWidth(n), y + i * f.getHeight()); } g.dispose(); }
}
