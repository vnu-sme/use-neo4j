package org.uet.dse.neo4j.gui;

import org.uet.dse.neo4j.manager.Neo4jDriverManager;
import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class ModelExplorerDialog extends JDialog {
    private JComboBox<String> cbModels;
    private String selectedModel = null;

    public ModelExplorerDialog(Frame parent) {
        super(parent, "Neo4j Repository Explorer", true);
        setSize(400, 250);
        setLayout(new BorderLayout(15, 15));

        List<String> models = fetchModelsFromDb();
        cbModels = new JComboBox<>(models.toArray(new String[0]));

        JPanel pnlContent = new JPanel(new GridLayout(0, 1, 5, 5));
        pnlContent.add(new JLabel("Available Models in Database:"));
        pnlContent.add(cbModels);
        add(pnlContent, BorderLayout.CENTER);

        JButton btnPull = new JButton("Pull Selected Model");
        btnPull.addActionListener(e -> {
            selectedModel = (String) cbModels.getSelectedItem();
            dispose();
        });
        add(btnPull, BorderLayout.SOUTH);

        setLocationRelativeTo(parent);
    }

    private List<String> fetchModelsFromDb() {
        List<String> results = new ArrayList<>();
        try (var session = Neo4jDriverManager.getInstance().openSession()) {
            var res = session.run("MATCH (m:ManageModel) RETURN m.name as name");
            while (res.hasNext()) results.add(res.next().get("name").asString());
        } catch (Exception e) { e.printStackTrace(); }
        return results;
    }

    public String getSelectedModelName() { return selectedModel; }
}