package org.uet.dse.neo4j.gui;

import org.uet.dse.neo4j.model_trafo.gui.Neo4jCorrObjectRepository;

import javax.swing.*;
import java.awt.*;

public class TggObjectCreationDialog extends JDialog {
    private JTextField txtName, txtClass, txtSrcModel, txtTrgModel;
    private Neo4jCorrObjectRepository repo;

    public TggObjectCreationDialog(Frame owner, Neo4jCorrObjectRepository repo) {
        super(owner, "Create New Correspondence Object", true);
        this.repo = repo;
        initComponents();
    }

    private void initComponents() {
        setSize(450, 300);
        setLocationRelativeTo(getOwner());
        setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 10, 5, 10);

        // Source Model Name
        gbc.gridy = 0; gbc.gridx = 0; add(new JLabel("Source Model:"), gbc);
        gbc.gridx = 1; txtSrcModel = new JTextField("Families"); add(txtSrcModel, gbc);

        // Target Model Name
        gbc.gridy = 1; gbc.gridx = 0; add(new JLabel("Target Model:"), gbc);
        gbc.gridx = 1; txtTrgModel = new JTextField("Persons"); add(txtTrgModel, gbc);

        // Correspondence Class
        gbc.gridy = 2; gbc.gridx = 0; add(new JLabel("Class (e.g. FR2PR):"), gbc);
        gbc.gridx = 1; txtClass = new JTextField("FR2PR"); add(txtClass, gbc);

        // Object ID
        gbc.gridy = 3; gbc.gridx = 0; add(new JLabel("Object ID:"), gbc);
        gbc.gridx = 1; txtName = new JTextField("c1"); add(txtName, gbc);

        // Buttons
        JPanel pnlBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton btnCancel = new JButton("Cancel");
        JButton btnSave = new JButton("Create Object");

        btnSave.addActionListener(e -> {
            String src = txtSrcModel.getText().trim();
            String trg = txtTrgModel.getText().trim();
            String cls = txtClass.getText().trim();
            String name = txtName.getText().trim();

            if (!src.isEmpty() && !trg.isEmpty() && !cls.isEmpty() && !name.isEmpty()) {
                repo.createCorrObject(cls, name, src, trg);
                JOptionPane.showMessageDialog(this, "Object '" + name + "' created successfully!");
                dispose();
            } else {
                JOptionPane.showMessageDialog(this, "Please fill all fields!");
            }
        });

        pnlBtns.add(btnCancel);
        pnlBtns.add(btnSave);
        gbc.gridy = 4; gbc.gridx = 0; gbc.gridwidth = 2;
        add(pnlBtns, gbc);
    }
}