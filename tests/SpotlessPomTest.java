import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

class SpotlessPomTest {
  public static void main(String[] args) throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    var builder = factory.newDocumentBuilder();
    Element project = builder.parse(Path.of(args[0]).toFile()).getDocumentElement();
    Element source = builder.parse(Path.of(args[1]).toFile()).getDocumentElement();
    String namespace = project.getNamespaceURI();
    for (String name : new String[] {"parent", "modules", "dependencies", "dependencyManagement"}) {
      check(
          project.getElementsByTagNameNS(namespace, name).getLength() == 0,
          "Formatter must not load " + name);
    }
    var plugins = project.getElementsByTagNameNS(namespace, "plugin");
    check(plugins.getLength() == 1, "Formatter must load only Spotless");
    Element expected = null;
    var sourcePlugins = source.getElementsByTagNameNS(namespace, "plugin");
    for (int i = 0; i < sourcePlugins.getLength(); i++) {
      var candidate = (Element) sourcePlugins.item(i);
      if ("spotless-maven-plugin"
          .equals(
              candidate.getElementsByTagNameNS(namespace, "artifactId").item(0).getTextContent())) {
        expected = candidate;
        break;
      }
    }
    check(expected != null, "Source Spotless plugin missing");
    removeWhitespace(project);
    removeWhitespace(source);
    check(plugins.item(0).isEqualNode(expected), "Shared formatter rules changed");
    check(
        project
            .getElementsByTagNameNS(namespace, "properties")
            .item(0)
            .isEqualNode(source.getElementsByTagNameNS(namespace, "properties").item(0)),
        "Shared formatter properties changed");
  }

  private static void removeWhitespace(Node node) {
    for (Node child = node.getFirstChild(); child != null; ) {
      Node next = child.getNextSibling();
      if (child.getNodeType() == Node.TEXT_NODE && child.getTextContent().isBlank()) {
        node.removeChild(child);
      } else {
        removeWhitespace(child);
      }
      child = next;
    }
  }

  private static void check(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
