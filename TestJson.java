import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
public class TestJson {
    public static class SmscSupplier {
        private Long id;
        private boolean active;
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }
    }
    public static void main(String[] args) throws Exception {
        String json = "[{\"supplier\":{\"id\":2,\"active\":true}}]";
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        JsonNode rootNode = objectMapper.readTree(json);
        if (rootNode != null && rootNode.isArray()) {
            System.out.println("Is array: " + rootNode.isArray());
            for (JsonNode node : rootNode) {
                if (node.has("supplier")) {
                    SmscSupplier s = objectMapper.treeToValue(node.get("supplier"), SmscSupplier.class);
                    System.out.println("ID: " + s.getId() + ", Active: " + s.isActive());
                }
            }
        }
    }
}
