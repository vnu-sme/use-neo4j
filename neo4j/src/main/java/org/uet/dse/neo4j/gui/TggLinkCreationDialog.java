package org.uet.dse.neo4j.gui;

import org.uet.dse.neo4j.model_trafo.gui.Neo4jCorrObjectRepository;
import javax.swing.*;
import java.awt.*;

public class TggLinkCreationDialog extends JDialog {
    private JTextField txtSrcOID, txtTrgOID, txtAssocName, txtSrcCls, txtTrgCls;
    private Neo4jCorrObjectRepository repo;

    public TggLinkCreationDialog(Frame owner, Neo4jCorrObjectRepository repo) {
        super(owner, "Connect Two Objects (Link)", true);
        this.repo = repo;
        initComponents();
    }

    private void initComponents() {
        setSize(450, 350);
        setLocationRelativeTo(getOwner());
        setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 10, 5, 10);

        // Các ô nhập liệu
        addLabelAndField("Source Object OID:", txtSrcOID = new JTextField(), gbc, 0);
        addLabelAndField("Target Object OID:", txtTrgOID = new JTextField(), gbc, 1);
        addLabelAndField("Association Name:", txtAssocName = new JTextField(), gbc, 2);
        addLabelAndField("Source Class Name:", txtSrcCls = new JTextField(), gbc, 3);
        addLabelAndField("Target Class Name:", txtTrgCls = new JTextField(), gbc, 4);

        JButton btnSave = new JButton("Connect Nodes");
        btnSave.addActionListener(e -> {
            repo.createCorrLink(
                txtSrcOID.getText().trim(),
                txtTrgOID.getText().trim(),
                txtAssocName.getText().trim()
            );
            dispose();
        });

        gbc.gridy = 5; gbc.gridx = 1;
        add(btnSave, gbc);
    }

    private void addLabelAndField(String label, JTextField field, GridBagConstraints gbc, int y) {
        gbc.gridy = y; gbc.gridx = 0; add(new JLabel(label), gbc);
        gbc.gridx = 1; add(field, gbc);
    }
}