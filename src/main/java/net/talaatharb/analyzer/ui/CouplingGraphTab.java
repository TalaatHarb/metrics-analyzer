package net.talaatharb.analyzer.ui;

import com.mxgraph.layout.hierarchical.mxHierarchicalLayout;
import com.mxgraph.swing.mxGraphComponent;
import com.mxgraph.view.mxGraph;
import javafx.application.Platform;
import javafx.embed.swing.SwingNode;
import javafx.geometry.Insets;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import net.talaatharb.analyzer.model.AnalysisResult;
import net.talaatharb.analyzer.model.DependencyRelation;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CouplingGraphTab {
    private static final String GRAPH_CLASS_LEVEL = "Class/File Coupling";
    private static final String GRAPH_PACKAGE_LEVEL = "Package Coupling";
    private static final String EDGE_STYLE_NORMAL =
            "endArrow=classic;strokeWidth=1.5;curved=1;";
    private static final String EDGE_STYLE_BIDIRECTIONAL =
            "endArrow=classic;strokeColor=#dc2626;strokeWidth=2.2;curved=1;";
    private static final String NODE_STYLE =
            "shape=rectangle;rounded=1;arcSize=10;fillColor=#eef2ff;strokeColor=#4f46e5;fontSize=11;";
    private static final String[] EDGE_COLORS = {
            "#3b82f6", "#10b981", "#f59e0b", "#8b5cf6", "#6366f1", "#ec4899", "#14b8a6", "#f43f5e"
    };

    private SwingNode graphNode;
    private ComboBox<String> graphLevelCombo;
    private AnalysisResult latestResult;
    private Tab tab;

    public Tab createTab() {
        if (tab != null) {
            return tab;
        }

        graphLevelCombo = new ComboBox<>();
        graphLevelCombo.getItems().addAll(GRAPH_CLASS_LEVEL, GRAPH_PACKAGE_LEVEL);
        graphLevelCombo.getSelectionModel().selectFirst();
        graphLevelCombo.setOnAction(event -> renderCouplingGraph());

        Label legend = new Label("Gray edges: one-way coupling | Red edges: two-way coupling");
        graphNode = new SwingNode();
        graphNode.boundsInLocalProperty().addListener((obs, oldBounds, newBounds) -> {
            SwingUtilities.invokeLater(() -> {
                if (graphNode.getContent() != null) {
                    graphNode.getContent().revalidate();
                    graphNode.getContent().repaint();
                }
            });
        });

        VBox controls = new VBox(
                8,
                new HBox(10, new Label("View:"), graphLevelCombo),
                legend
        );
        controls.setPadding(new Insets(12, 12, 0, 12));

        BorderPane content = new BorderPane();
        content.setTop(controls);
        content.setCenter(graphNode);
        BorderPane.setMargin(graphNode, new Insets(12));

        renderPlaceholder("Run an analysis to visualize coupling relations.");

        tab = new Tab("Coupling Graph", content);
        tab.setClosable(false);
        return tab;
    }

    public void setAnalysisResult(AnalysisResult latestResult) {
        this.latestResult = latestResult;
        renderCouplingGraph();
    }

    public void showPlaceholder(String message) {
        latestResult = null;
        renderPlaceholder(message);
    }

    private void renderCouplingGraph() {
        if (latestResult == null) {
            renderPlaceholder("Run an analysis to visualize coupling relations.");
            return;
        }

        boolean packageLevel = GRAPH_PACKAGE_LEVEL.equals(graphLevelCombo.getValue());
        List<DependencyRelation> relations = packageLevel
                ? latestResult.getPackageCouplings()
                : latestResult.getClassCouplings();

        if (relations.isEmpty()) {
            renderPlaceholder("No " + (packageLevel ? "package" : "class/file") + " coupling relations found.");
            return;
        }

        Set<String> edgeKeys = new LinkedHashSet<>();
        Set<String> allNodes = new LinkedHashSet<>();
        for (DependencyRelation relation : relations) {
            edgeKeys.add(edgeKey(relation.getSource(), relation.getTarget()));
            allNodes.add(relation.getSource());
            allNodes.add(relation.getTarget());
        }

        List<String> sortedNodes = new ArrayList<>(allNodes);
        sortedNodes.sort((a, b) -> {
            String[] partsA = a.split("\\.");
            String[] partsB = b.split("\\.");
            int minLen = Math.min(partsA.length, partsB.length);
            for (int i = 0; i < minLen; i++) {
                boolean aIsDir = i < partsA.length - 1 || packageLevel;
                boolean bIsDir = i < partsB.length - 1 || packageLevel;
                if (aIsDir != bIsDir) {
                    return aIsDir ? -1 : 1;
                }
                int cmp = partsA[i].compareTo(partsB[i]);
                if (cmp != 0) {
                    return cmp;
                }
            }
            return Integer.compare(partsA.length, partsB.length);
        });

        SwingUtilities.invokeLater(() -> {
            mxGraph graph = new mxGraph();
            Object parent = graph.getDefaultParent();
            Map<String, Object> vertices = new LinkedHashMap<>();

            graph.getModel().beginUpdate();
            try {
                for (String node : sortedNodes) {
                    vertices.computeIfAbsent(node,
                            key -> graph.insertVertex(parent, null, shortLabel(key), 0, 0, 190, 55, NODE_STYLE));
                }

                for (DependencyRelation relation : relations) {
                    String forward = edgeKey(relation.getSource(), relation.getTarget());
                    String reverse = edgeKey(relation.getTarget(), relation.getSource());
                    boolean bidirectional = edgeKeys.contains(forward) && edgeKeys.contains(reverse);

                    if (bidirectional && forward.compareTo(reverse) > 0) {
                        continue;
                    }

                    String style = bidirectional ? EDGE_STYLE_BIDIRECTIONAL : EDGE_STYLE_NORMAL;
                    if (bidirectional) {
                        style = "startArrow=classic;" + style;
                    }

                    if (!bidirectional) {
                        int colorIndex = Math.abs(relation.getSource().hashCode()) % EDGE_COLORS.length;
                        style = style + "strokeColor=" + EDGE_COLORS[colorIndex] + ";";
                    }

                    graph.insertEdge(
                            parent,
                            null,
                            "",
                            vertices.get(relation.getSource()),
                            vertices.get(relation.getTarget()),
                            style
                    );
                }
            } finally {
                graph.getModel().endUpdate();
            }

            mxHierarchicalLayout layout = new mxHierarchicalLayout(graph);
            layout.setOrientation(javax.swing.SwingConstants.WEST);
            layout.setIntraCellSpacing(50.0);
            layout.setInterRankCellSpacing(100.0);
            layout.execute(parent);

            mxGraphComponent component = new mxGraphComponent(graph);
            component.setConnectable(false);
            component.setAutoExtend(true);
            component.setPanning(true);
            component.setToolTips(true);
            component.setOpaque(true);
            component.setBackground(java.awt.Color.WHITE);
            component.getViewport().setOpaque(true);
            component.getViewport().setBackground(java.awt.Color.WHITE);
            component.addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentResized(java.awt.event.ComponentEvent e) {
                    component.revalidate();
                    component.repaint();
                }
            });

            setSwingContent(component);
        });
    }

    private void renderPlaceholder(String message) {
        SwingUtilities.invokeLater(() -> {
            javax.swing.JPanel panel = new javax.swing.JPanel(new java.awt.BorderLayout());
            panel.setBackground(java.awt.Color.WHITE);
            panel.add(new javax.swing.JLabel(message, javax.swing.SwingConstants.CENTER), java.awt.BorderLayout.CENTER);
            Platform.runLater(() -> {
                graphNode.setContent(panel);
                graphNode.requestFocus();
            });
        });
    }

    private void setSwingContent(mxGraphComponent component) {
        Platform.runLater(() -> {
            graphNode.setContent(component);
            graphNode.requestFocus();
        });
        SwingUtilities.invokeLater(() -> {
            component.revalidate();
            component.refresh();
            component.repaint();
        });
    }

    private static String edgeKey(String source, String target) {
        return source + "->" + target;
    }

    private static String shortLabel(String text) {
        int idx = text.lastIndexOf('.');
        if (idx < 0 || idx + 1 >= text.length()) {
            return text;
        }
        return text.substring(idx + 1);
    }
}
