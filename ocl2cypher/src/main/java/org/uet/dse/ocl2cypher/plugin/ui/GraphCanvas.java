package org.uet.dse.ocl2cypher.plugin.ui;

import javax.swing.*;
import javax.swing.event.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.*;
import java.util.List;
import org.uet.dse.ocl2cypher.graph.GraphModel;
import org.uet.dse.ocl2cypher.execution.Neo4jExecutionAdapter;
import static org.uet.dse.ocl2cypher.plugin.ui.WorkbenchStyle.*;

final class GraphCanvas extends JPanel {
    private static final double NODE_RADIUS = 14;
    private static final Color[] COLORS = {
            new Color(92, 190, 137), new Color(234, 129, 94),
            new Color(104, 172, 215), new Color(199, 118, 190),
            new Color(225, 190, 91), new Color(124, 196, 190)
    };

    private final Map<String, Point2D.Double> nodePositions = new LinkedHashMap<>();
    private final Set<String> visibleNodeKeys = new LinkedHashSet<>();
    private final Set<String> expandedNodeKeys = new LinkedHashSet<>();
    private GraphModel model;
    private double zoom = 1;
    private double panX;
    private double panY;
    private String selectedNode;
    private String draggedNode;
    private GraphModel.Relationship draggedEdge;
    private Point2D.Double dragOffset;
    private Point2D.Double lastWorldPoint;
    private Point panOrigin;
    private Point pressOrigin;
    private boolean dragMoved;
    private boolean legend = true;

    GraphCanvas() {
        setBackground(EDITOR);
        setToolTipText("Click a node to expand its neighbours; drag to move or pan");
        ToolTipManager.sharedInstance().registerComponent(this);
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                pressOrigin = event.getPoint();
                dragMoved = false;
                Point2D.Double world = screenToWorld(event.getPoint());
                draggedNode = findNode(world);
                if (draggedNode != null) {
                    selectedNode = draggedNode;
                    Point2D.Double node = nodePositions.get(draggedNode);
                    dragOffset = new Point2D.Double(node.x - world.x, node.y - world.y);
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                    return;
                }
                draggedEdge = findEdge(world);
                if (draggedEdge != null) {
                    lastWorldPoint = world;
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                    return;
                }
                panOrigin = event.getPoint();
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            }

            @Override public void mouseReleased(MouseEvent event) {
                String clickedNode = draggedNode;
                boolean expand = clickedNode != null && !dragMoved
                        && SwingUtilities.isLeftMouseButton(event);
                draggedNode = null;
                draggedEdge = null;
                dragOffset = null;
                lastWorldPoint = null;
                panOrigin = null;
                pressOrigin = null;
                if (expand) expandNode(clickedNode);
                updateCursor(event.getPoint());
            }

            @Override public void mouseDragged(MouseEvent event) {
                if (pressOrigin != null && pressOrigin.distance(event.getPoint()) > 3) {
                    dragMoved = true;
                }
                if (draggedNode != null) {
                    Point2D.Double world = screenToWorld(event.getPoint());
                    nodePositions.put(draggedNode, new Point2D.Double(
                            world.x + dragOffset.x, world.y + dragOffset.y));
                    repaint();
                    return;
                }
                if (draggedEdge != null) {
                    Point2D.Double world = screenToWorld(event.getPoint());
                    double dx = world.x - lastWorldPoint.x;
                    double dy = world.y - lastWorldPoint.y;
                    moveNode(draggedEdge.sourceKey(), dx, dy);
                    if (!draggedEdge.sourceKey().equals(draggedEdge.targetKey())) {
                        moveNode(draggedEdge.targetKey(), dx, dy);
                    }
                    lastWorldPoint = world;
                    repaint();
                    return;
                }
                if (panOrigin != null) {
                    panX += event.getX() - panOrigin.x;
                    panY += event.getY() - panOrigin.y;
                    panOrigin = event.getPoint();
                    repaint();
                }
            }

            @Override public void mouseMoved(MouseEvent event) {
                updateCursor(event.getPoint());
            }

            @Override public void mouseWheelMoved(java.awt.event.MouseWheelEvent event) {
                zoomAt(event.getPoint(), Math.pow(1.08, -event.getPreciseWheelRotation()));
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    void setGraph(GraphModel graph) {
        model = graph;
        resetToSchema();
    }

    void resetToSchema() {
        visibleNodeKeys.clear();
        expandedNodeKeys.clear();
        nodePositions.clear();
        selectedNode = null;
        if (model != null) {
            visibleNodeKeys.addAll(initialSchemaNodeKeys(model));
            initializeLayout();
        }
        SwingUtilities.invokeLater(this::fit);
        repaint();
    }

    void zoomBy(double factor) {
        zoomAt(new Point(getWidth() / 2, getHeight() / 2), factor);
    }

    private void zoomAt(Point anchor, double factor) {
        double oldZoom = zoom;
        double newZoom = Math.max(.25, Math.min(4.0, oldZoom * factor));
        if (newZoom == oldZoom) return;
        Point2D.Double world = screenToWorld(anchor);
        zoom = newZoom;
        panX = anchor.x - getWidth() / 2.0 - world.x * zoom;
        panY = anchor.y - getHeight() / 2.0 - world.y * zoom;
        repaint();
    }

    void fit() {
        if (nodePositions.isEmpty() || getWidth() <= 1 || getHeight() <= 1) {
            zoom = 1;
            panX = panY = 0;
            repaint();
            return;
        }
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (Point2D.Double point : nodePositions.values()) {
            minX = Math.min(minX, point.x); minY = Math.min(minY, point.y);
            maxX = Math.max(maxX, point.x); maxY = Math.max(maxY, point.y);
        }
        double graphWidth = Math.max(1, maxX - minX + NODE_RADIUS * 4);
        double graphHeight = Math.max(1, maxY - minY + NODE_RADIUS * 4);
        zoom = Math.max(.25, Math.min(3.0,
                Math.min((getWidth() - 70.0) / graphWidth,
                        (getHeight() - 70.0) / graphHeight)));
        panX = -((minX + maxX) / 2.0) * zoom;
        panY = -((minY + maxY) / 2.0) * zoom;
        repaint();
    }

    void toggleLegend() {
        legend = !legend;
        repaint();
    }

    @Override public String getToolTipText(MouseEvent event) {
        Point2D.Double world = screenToWorld(event.getPoint());
        String nodeKey = findNode(world);
        if (nodeKey != null && model != null) {
            GraphModel.Node node = model.node(nodeKey);
            return node == null ? nodeKey : Neo4jExecutionAdapter.displayLabel(node) + " — " + node.projection()
                    + " — " + node.stableKey();
        }
        GraphModel.Relationship edge = findEdge(world);
        if (edge != null) return edge.physicalType() + ": " + edge.sourceKey()
                + " → " + edge.targetKey();
        return "Click a node to expand its neighbours; drag to move or pan; wheel to zoom";
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        if (model == null || nodePositions.isEmpty()) {
            String text = model == null
                    ? "Load a USE/SOIL snapshot to preview its property graph"
                    : "This model has no schema nodes to display";
            g.setColor(MUTED);
            FontMetrics metrics = g.getFontMetrics();
            g.drawString(text, (getWidth() - metrics.stringWidth(text)) / 2,
                    Math.max(30, getHeight() / 2));
            g.dispose();
            return;
        }

        AffineTransform old = g.getTransform();
        g.translate(getWidth() / 2.0 + panX, getHeight() / 2.0 + panY);
        g.scale(zoom, zoom);
        paintEdges(g);
        paintNodes(g);
        g.setTransform(old);
        if (legend) paintLegend(g);
        g.dispose();
    }

    private void paintEdges(Graphics2D g) {
        boolean labelsVisible = zoom >= .65 && visibleRelationshipCount() <= 80;
        for (GraphModel.Relationship edge : model.relationships()) {
            Point2D.Double source = nodePositions.get(edge.sourceKey());
            Point2D.Double target = nodePositions.get(edge.targetKey());
            if (source == null || target == null) continue;
            boolean selected = edge == draggedEdge;
            g.setColor(selected ? ACCENT.brighter() : new Color(119, 124, 134, 165));
            g.setStroke(new BasicStroke((float) ((selected ? 2.2 : 1.0) / zoom)));
            drawArrow(g, source, target);
            if (labelsVisible) paintEdgeLabel(g, edge, source, target);
        }
    }

    private void paintNodes(Graphics2D g) {
        List<GraphModel.Node> nodes = new ArrayList<>(model.nodes());
        nodes.sort(Comparator.comparing(GraphModel.Node::stableKey));
        for (GraphModel.Node node : nodes) {
            Point2D.Double point = nodePositions.get(node.stableKey());
            if (point == null) continue;
            Color color = COLORS[node.projection().ordinal() % COLORS.length];
            Shape circle = new Ellipse2D.Double(point.x - NODE_RADIUS,
                    point.y - NODE_RADIUS, NODE_RADIUS * 2, NODE_RADIUS * 2);
            g.setColor(new Color(0, 0, 0, 90));
            g.fill(new Ellipse2D.Double(point.x - NODE_RADIUS + 2,
                    point.y - NODE_RADIUS + 3, NODE_RADIUS * 2, NODE_RADIUS * 2));
            g.setColor(color);
            g.fill(circle);
            boolean selected = node.stableKey().equals(selectedNode)
                    || node.stableKey().equals(draggedNode);
            g.setColor(selected ? Color.WHITE : color.brighter());
            g.setStroke(new BasicStroke((float) ((selected
                    ? 2.3 : 1.0) / zoom)));
            g.draw(circle);
            String label = Neo4jExecutionAdapter.displayLabel(node);
            if (label.length() > 15) label = label.substring(0, 14) + "…";
            g.setFont(g.getFont().deriveFont((float) (9 / Math.max(.65, zoom))));
            g.setColor(TEXT);
            FontMetrics metrics = g.getFontMetrics();
            g.drawString(label, (float) (point.x - metrics.stringWidth(label) / 2.0),
                    (float) (point.y + NODE_RADIUS + 12 / zoom));
        }
    }

    private void expandNode(String nodeKey) {
        if (model == null || nodeKey == null || !visibleNodeKeys.contains(nodeKey)) return;
        selectedNode = nodeKey;
        if (!expandedNodeKeys.add(nodeKey)) {
            repaint();
            return;
        }

        List<String> neighbours = hiddenNeighbourKeys(model, nodeKey, visibleNodeKeys);
        Point2D.Double centre = nodePositions.getOrDefault(nodeKey,
                new Point2D.Double(0, 0));
        int index = 0;
        int total = neighbours.size();
        for (String neighbour : neighbours) {
            if (!hasNode(neighbour)) continue;
            visibleNodeKeys.add(neighbour);
            nodePositions.put(neighbour,
                    expansionPosition(centre, index++, Math.max(1, total)));
        }
        SwingUtilities.invokeLater(this::fit);
        repaint();
    }

    private boolean hasNode(String stableKey) {
        return model.nodes().stream().anyMatch(node -> node.stableKey().equals(stableKey));
    }

    private Point2D.Double expansionPosition(Point2D.Double centre, int index, int total) {
        int ring = index / 8;
        int ringIndex = index % 8;
        int ringCount = Math.min(8, total - ring * 8);
        double angle = -Math.PI / 2 + Math.PI * 2 * ringIndex / Math.max(1, ringCount);
        double radius = 72 + ring * 54;
        Point2D.Double candidate = new Point2D.Double();
        for (int attempt = 0; attempt < 6; attempt++) {
            candidate.setLocation(centre.x + Math.cos(angle) * radius,
                    centre.y + Math.sin(angle) * radius);
            boolean overlaps = false;
            for (Point2D.Double point : nodePositions.values()) {
                if (point.distance(candidate) < NODE_RADIUS * 3.1) {
                    overlaps = true;
                    break;
                }
            }
            if (!overlaps) break;
            radius += 30;
        }
        return candidate;
    }

    private long visibleRelationshipCount() {
        return model.relationships().stream()
                .filter(edge -> visibleNodeKeys.contains(edge.sourceKey())
                        && visibleNodeKeys.contains(edge.targetKey()))
                .count();
    }

    private void initializeLayout() {
        List<GraphModel.Node> nodes = model.nodes().stream()
                .filter(node -> visibleNodeKeys.contains(node.stableKey()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        nodes.sort(Comparator.comparing(GraphModel.Node::stableKey));
        int count = Math.max(1, nodes.size());
        double baseRadius = Math.max(90, Math.sqrt(count) * 34);
        for (int i = 0; i < nodes.size(); i++) {
            double angle = -Math.PI / 2 + Math.PI * 2 * i / count;
            double ring = baseRadius * (.72 + .28 * ((i % 3) / 2.0));
            nodePositions.put(nodes.get(i).stableKey(), new Point2D.Double(
                    Math.cos(angle) * ring, Math.sin(angle) * ring * .72));
        }
    }

    private Point2D.Double screenToWorld(Point point) {
        return new Point2D.Double(
                (point.x - getWidth() / 2.0 - panX) / zoom,
                (point.y - getHeight() / 2.0 - panY) / zoom);
    }

    private String findNode(Point2D point) {
        String nearest = null;
        double nearestDistance = NODE_RADIUS + 4 / zoom;
        for (Map.Entry<String, Point2D.Double> entry : nodePositions.entrySet()) {
            double distance = entry.getValue().distance(point);
            if (distance <= nearestDistance) {
                nearest = entry.getKey();
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private GraphModel.Relationship findEdge(Point2D point) {
        if (model == null) return null;
        GraphModel.Relationship nearest = null;
        double nearestDistance = 7 / zoom;
        for (GraphModel.Relationship edge : model.relationships()) {
            Point2D.Double source = nodePositions.get(edge.sourceKey());
            Point2D.Double target = nodePositions.get(edge.targetKey());
            if (source == null || target == null) continue;
            double distance = Line2D.ptSegDist(source.x, source.y, target.x, target.y,
                    point.getX(), point.getY());
            if (distance <= nearestDistance) {
                nearest = edge;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private void moveNode(String key, double dx, double dy) {
        Point2D.Double point = nodePositions.get(key);
        if (point != null) point.setLocation(point.x + dx, point.y + dy);
    }

    private void updateCursor(Point point) {
        Point2D.Double world = screenToWorld(point);
        boolean movable = findNode(world) != null || findEdge(world) != null;
        setCursor(Cursor.getPredefinedCursor(movable
                ? Cursor.MOVE_CURSOR : Cursor.DEFAULT_CURSOR));
    }

    private static void drawArrow(Graphics2D g, Point2D.Double source,
                                  Point2D.Double target) {
        g.draw(new Line2D.Double(source, target));
        double angle = Math.atan2(target.y - source.y, target.x - source.x);
        double tipX = target.x - Math.cos(angle) * NODE_RADIUS;
        double tipY = target.y - Math.sin(angle) * NODE_RADIUS;
        Path2D arrow = new Path2D.Double();
        arrow.moveTo(tipX, tipY);
        arrow.lineTo(tipX - Math.cos(angle - .48) * 7,
                tipY - Math.sin(angle - .48) * 7);
        arrow.lineTo(tipX - Math.cos(angle + .48) * 7,
                tipY - Math.sin(angle + .48) * 7);
        arrow.closePath();
        g.fill(arrow);
    }

    private static void paintEdgeLabel(Graphics2D g, GraphModel.Relationship edge,
                                       Point2D.Double source, Point2D.Double target) {
        String label = edge.physicalType();
        if (label.length() > 18) label = label.substring(0, 17) + "…";
        float x = (float) ((source.x + target.x) / 2.0);
        float y = (float) ((source.y + target.y) / 2.0 - 3);
        g.setFont(g.getFont().deriveFont(7.5f));
        g.setColor(MUTED);
        g.drawString(label, x, y);
    }

    private void paintLegend(Graphics2D g) {
        int x = Math.max(6, getWidth() - 137), y = 7;
        g.setColor(new Color(25, 27, 31, 220));
        g.fillRoundRect(x, y, 130, 91, 5, 5);
        g.setColor(TEXT);
        g.setFont(g.getFont().deriveFont(Font.BOLD, 9f));
        g.drawString("Projection", x + 7, y + 13);
        GraphModel.Projection[] projections = GraphModel.Projection.values();
        g.setFont(g.getFont().deriveFont(8f));
        for (int i = 0; i < projections.length; i++) {
            g.setColor(COLORS[i]);
            g.fillOval(x + 8, y + 20 + i * 10, 7, 7);
            g.setColor(MUTED);
            g.drawString(projections[i].name(), x + 20, y + 27 + i * 10);
        }
        g.drawString("Click node = expand", x + 7, y + 70);
        g.drawString("Visible " + visibleNodeKeys.size() + "/" + model.nodes().size(),
                x + 7, y + 82);
    }

    static Set<String> initialSchemaNodeKeys(GraphModel model) {
        Set<String> keys = new LinkedHashSet<>();
        if (model == null) return keys;
        model.nodes().stream()
                .filter(node -> node.projection() == GraphModel.Projection.SCHEMA)
                .map(GraphModel.Node::stableKey)
                .sorted()
                .forEach(keys::add);
        return keys;
    }

    static List<String> hiddenNeighbourKeys(GraphModel model, String nodeKey,
                                             Set<String> visibleNodeKeys) {
        if (model == null || nodeKey == null || visibleNodeKeys == null) return List.of();
        Set<String> neighbours = new TreeSet<>();
        for (GraphModel.Relationship relationship : model.relationships()) {
            if (relationship.sourceKey().equals(nodeKey)) {
                neighbours.add(relationship.targetKey());
            }
            if (relationship.targetKey().equals(nodeKey)) {
                neighbours.add(relationship.sourceKey());
            }
        }
        neighbours.removeAll(visibleNodeKeys);
        return List.copyOf(neighbours);
    }
}
