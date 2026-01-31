package org.uet.dse.neo4j.gui;

import org.uet.dse.neo4j.migration.StructuralImpactAnalysis;
import javax.swing.*;
import java.awt.*;

public class StructuralImpactDialog extends JDialog {
    private boolean confirmed = false;

    public StructuralImpactDialog(Frame parent, StructuralImpactAnalysis impact) {
        super(parent, "Critical: Structural Mismatch", true);
        setSize(500, 400);
        setLayout(new BorderLayout(15, 15));

        JTextArea txtReport = new JTextArea(impact.generateImpactReport());
        txtReport.setEditable(false);
        txtReport.setForeground(Color.RED);
        txtReport.setFont(new Font("Monospaced", Font.BOLD, 12));

        add(new JLabel(" The proposed Model Change will destroy existing data:"), BorderLayout.NORTH);
        add(new JScrollPane(txtReport), BorderLayout.CENTER);

        JPanel pnlBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton btnConfirm = new JButton("Confirm & Delete Objects");
        btnConfirm.setBackground(Color.RED);
        btnConfirm.setForeground(Color.WHITE);
        
        JButton btnAbort = new JButton("Abort Sync");

        btnConfirm.addActionListener(e -> {
            confirmed = true;
            dispose();
        });
        btnAbort.addActionListener(e -> dispose());

        pnlBtns.add(btnAbort);
        pnlBtns.add(btnConfirm);
        add(pnlBtns, BorderLayout.SOUTH);
        
        setLocationRelativeTo(parent);
    }

    public boolean isConfirmed() { return confirmed; }
}