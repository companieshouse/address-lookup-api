import java.nio.file.Path;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

class SpotlessPom {
  public static void main(String[] args) throws Exception {
    if (args.length != 2) {
      throw new IllegalArgumentException("Usage: java SpotlessPom.java SOURCE_POM OUTPUT_POM");
    }
    var factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    var builder = factory.newDocumentBuilder();
    var source = builder.parse(Path.of(args[0]).toFile()).getDocumentElement();
    Element plugin = null;
    for (Node node = child(child(source, "build"), "plugins").getFirstChild();
        node != null;
        node = node.getNextSibling()) {
      if (node instanceof Element candidate
          && "plugin".equals(candidate.getLocalName())
          && "spotless-maven-plugin".equals(child(candidate, "artifactId").getTextContent())) {
        plugin = candidate;
        break;
      }
    }
    if (plugin == null) {
      throw new IllegalArgumentException("Spotless plugin missing from the root POM.");
    }
    Document output = builder.newDocument();
    String namespace = source.getNamespaceURI();
    Element project = output.createElementNS(namespace, "project");
    output.appendChild(project);
    for (String[] field :
        new String[][] {
          {"modelVersion", "4.0.0"},
          {"groupId", "local.formatting"},
          {"artifactId", "spotless-standalone"},
          {"version", "1"},
          {"packaging", "pom"}
        }) {
      Element element = output.createElementNS(namespace, field[0]);
      element.setTextContent(field[1]);
      project.appendChild(element);
    }
    project.appendChild(output.importNode(child(source, "properties"), true));
    Element build = output.createElementNS(namespace, "build");
    Element plugins = output.createElementNS(namespace, "plugins");
    project.appendChild(build);
    build.appendChild(plugins);
    plugins.appendChild(output.importNode(plugin, true));

    var transformers = TransformerFactory.newInstance();
    transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
    var transformer = transformers.newTransformer();
    transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
    transformer.transform(new DOMSource(output), new StreamResult(Path.of(args[1]).toFile()));
  }

  private static Element child(Element parent, String name) {
    for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (node instanceof Element element && name.equals(element.getLocalName())) {
        return element;
      }
    }
    throw new IllegalArgumentException("Missing " + name + " in " + parent.getLocalName());
  }
}
