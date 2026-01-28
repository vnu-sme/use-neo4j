package org.uet.dse.neo4j.gui;

import org.uet.dse.neo4j.config.SyncConfig;
import javax.swing.*;
import java.awt.*;

public class SyncSettingsDialog extends JDialog {
    private JCheckBox chkPushModel, chkPushObject, chkPullModel, chkPullObject;

    public SyncSettingsDialog(Frame parent) {
        super(parent, "Sync Settings", true);
        this.setLayout(new BorderLayout());
        
        JPanel pnlMain = new JPanel();
        pnlMain.setLayout(new BoxLayout(pnlMain, BoxLayout.Y_AXIS));
        pnlMain.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // --- Group 1: Outgoing Sync (USE to Neo4j) ---
        JPanel pnlPush = new JPanel(new GridLayout(0, 1));
        pnlPush.setBorder(BorderFactory.createTitledBorder("Outgoing (USE -> Neo4j)"));
        chkPushModel = new JCheckBox("Immediate Sync Model on Import", SyncConfig.getInstance().autoPushModelOnImport);
        chkPushObject = new JCheckBox("Immediate Sync Object on Change (Add/Edit/Delete)", SyncConfig.getInstance().autoPushObjectOnChange);
        pnlPush.add(chkPushModel);
        pnlPush.add(chkPushObject);

        // --- Group 2: Incoming Sync (Neo4j to USE) ---
        JPanel pnlPull = new JPanel(new GridLayout(0, 1));
        pnlPull.setBorder(BorderFactory.createTitledBorder("Incoming (Neo4j -> USE)"));
        chkPullModel = new JCheckBox("Immediate Receive Model changes from DB", SyncConfig.getInstance().autoPullModelOnChange);
        chkPullObject = new JCheckBox("Immediate Receive Object changes from DB", SyncConfig.getInstance().autoPullObjectOnChange);
        pnlPull.add(chkPullModel);
        pnlPull.add(chkPullObject);

        pnlMain.add(pnlPush);
        pnlMain.add(Box.createVerticalStrut(10));
        pnlMain.add(pnlPull);

        // --- Buttons ---
        JPanel pnlBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton btnSave = new JButton("Save Config");
        JButton btnCancel = new JButton("Cancel");

        btnSave.addActionListener(e -> {
            SyncConfig config = SyncConfig.getInstance();
            config.autoPushModelOnImport = chkPushModel.isSelected();
            config.autoPushObjectOnChange = chkPushObject.isSelected();
            config.autoPullModelOnChange = chkPullModel.isSelected();
            config.autoPullObjectOnChange = chkPullObject.isSelected();
            config.save();
            // Log để kiểm tra giá trị sau khi lưu
            System.out.println("Settings Updated: autoPushObject = " + config.autoPushObjectOnChange);
            JOptionPane.showMessageDialog(this, "Settings saved!");
            dispose();
        });

        btnCancel.addActionListener(e -> dispose());
        pnlBtns.add(btnSave);
        pnlBtns.add(btnCancel);

        this.add(pnlMain, BorderLayout.CENTER);
        this.add(pnlBtns, BorderLayout.SOUTH);
        this.pack();
        this.setLocationRelativeTo(parent);
    }
}