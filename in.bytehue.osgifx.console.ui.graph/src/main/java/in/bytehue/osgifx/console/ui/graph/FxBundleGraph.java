package in.bytehue.osgifx.console.ui.graph;

import java.util.Collection;

import org.jgrapht.GraphPath;
import org.jgrapht.graph.DefaultEdge;

import in.bytehue.osgifx.console.smartgraph.graph.DigraphEdgeList;
import in.bytehue.osgifx.console.smartgraph.graph.Edge;
import in.bytehue.osgifx.console.smartgraph.graph.Graph;
import in.bytehue.osgifx.console.smartgraph.graph.Vertex;

public final class FxBundleGraph {

    Graph<BundleVertex, String> graph;

    public FxBundleGraph(final Collection<GraphPath<BundleVertex, DefaultEdge>> graphPaths) {
        graph = buildGraph(graphPaths);
    }

    public Graph<BundleVertex, String> getGraph() {
        return graph;
    }

    private Graph<BundleVertex, String> buildGraph(final Collection<GraphPath<BundleVertex, DefaultEdge>> graphPaths) {
        final Graph<BundleVertex, String> graph = new DigraphEdgeList<>();
        for (final GraphPath<BundleVertex, DefaultEdge> path : graphPaths) {
            for (final DefaultEdge edge : path.getEdgeList()) {
                final BundleVertex source = path.getGraph().getEdgeSource(edge);
                final BundleVertex target = path.getGraph().getEdgeTarget(edge);
                if (graph.vertices().stream().noneMatch(v -> v.element().equals(source))) {
                    graph.insertVertex(source);
                }
                if (graph.vertices().stream().noneMatch(v -> v.element().equals(target))) {
                    graph.insertVertex(target);
                }
                if (!containsEdge(graph.edges(), source, target)) {
                    graph.insertEdge(source, target, source + "->" + target);
                }
            }
        }
        return graph;
    }

    private boolean containsEdge(final Collection<Edge<String, BundleVertex>> edges, final BundleVertex source, final BundleVertex target) {
        boolean isEdgeFound = false;
        for (final Edge<String, BundleVertex> edge : edges) {
            boolean isEdgeSourceFound = false;
            boolean isEdgeTargetFound = false;
            for (final Vertex<BundleVertex> vertex : edge.vertices()) {
                if (isSameVertex(vertex, source)) {
                    isEdgeSourceFound = true;
                }
            }
            for (final Vertex<BundleVertex> vertex : edge.vertices()) {
                if (isSameVertex(vertex, target)) {
                    isEdgeTargetFound = true;
                }
            }
            if (isEdgeSourceFound && isEdgeTargetFound) {
                isEdgeFound = true;
            }
        }
        return isEdgeFound;
    }

    private boolean isSameVertex(final Vertex<BundleVertex> vertex, final BundleVertex check) {
        return vertex.element().equals(check);
    }

}