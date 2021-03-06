package me.andrz.jackson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 *
 */
public class JsonReferenceProcessor {

    private static final Logger logger = LoggerFactory.getLogger(JsonReferenceProcessor.class);

    private int maxDepth = 1;
    private boolean stopOnCircular = true;
    private boolean preserveRefs = false;
    private boolean preserveLocalRefs = false;
    private boolean preserveExternalRefs = false;
    private boolean cacheInMemory = true;
    private String refPrefix = "x-$ref";
    private ObjectMapperFactory mapperFactory;

    public boolean isCacheInMemory() { return cacheInMemory; }

    public void setCacheInMemory(boolean cacheInMemory) { this.cacheInMemory = cacheInMemory; }

    public int getMaxDepth() {
        return maxDepth;
    }

    public void setMaxDepth(int maxDepth) {
        this.maxDepth = maxDepth;
    }

    public boolean isStopOnCircular() {
        return stopOnCircular;
    }

    public void setStopOnCircular(boolean stopOnCircular) {
        this.stopOnCircular = stopOnCircular;
    }

    public boolean isPreserveRefs() {
        return preserveRefs;
    }

    public void setPreserveRefs(boolean preserveRefs) {
        this.preserveRefs = preserveRefs;
    }

    public boolean isPreserveLocalRefs() {
		return preserveLocalRefs;
	}

    /**
     * If set local references (to other places within the same file) are not resolved and will stay as is.
     * 
     * @param preserveLocalRefs Preserve local references
     */
	public void setPreserveLocalRefs(boolean preserveLocalRefs) {
		this.preserveLocalRefs = preserveLocalRefs;
	}

	public boolean isPreserveExternalRefs() {
		return preserveExternalRefs;
	}

    /**
     * If set external references (to other files) are not resolved and will stay as is.
     * 
     * @param preserveExternalRefs Preserve external references
     */
	public void setPreserveExternalRefs(boolean preserveExternalRefs) {
		this.preserveExternalRefs = preserveExternalRefs;
	}

	public String getRefPrefix() {
        return refPrefix;
    }

    public void setRefPrefix(String refPrefix) {
        this.refPrefix = refPrefix;
    }

    public ObjectMapperFactory getMapperFactory() {
        return mapperFactory;
    }

    public void setMapperFactory(ObjectMapperFactory mapperFactory) {
        this.mapperFactory = mapperFactory;
    }

    ObjectMapperFactory someMapperFactory() {
        return mapperFactory == null ? DefaultObjectMapperFactory.instance : mapperFactory;
    }

    public JsonNode process(File file) throws JsonReferenceException, IOException {
        JsonContext context = new JsonContext(file);
        context.setFactory(someMapperFactory());
        return process(context);
    }

    public JsonNode process(URL url) throws JsonReferenceException, IOException {
        JsonContext context = new JsonContext(url);
        context.setFactory(someMapperFactory());
        return process(context);
    }

    public JsonNode process(JsonContext context, Set<JsonReference> processed) throws JsonReferenceException, IOException {
        if (context.getFactory() == DefaultObjectMapperFactory.instance)
            context.setFactory(someMapperFactory());
        JsonNode node = context.getNode();
        return process(context, node);
    }

    public JsonNode process(JsonContext context) throws JsonReferenceException, IOException {
        return process(context, context.getNode());
    }

    public JsonNode process(JsonContext context, JsonNode node) throws JsonReferenceException, IOException {

        if (node == null) {
            return node;
        }

        if (context.getProcessed() == null) {
            context.setProcessed(new HashSet<JsonReference>());
        }

        logger.trace("processed: " + context.getProcessed());

        // Check if the whole node must be replaced
        if (JsonReferenceNode.is(node)) {
            JsonNode replacementNode = getReplacementNode(node, context);
            logger.debug("replacing whole node with" + replacementNode);
            return replacementNode;
        }

        if (maxDepth >= 0 && context.getProcessed() != null && context.getProcessed().size() >= maxDepth) {
            return node;
        }

        if (node.isArray()) {
            ArrayNode arrayNode = (ArrayNode) node;
            Iterator<JsonNode> elements = arrayNode.elements();
            int i = 0;
            while (elements.hasNext()) {
                JsonNode subNode = elements.next();

                logger.trace("i=" + i);

                if (JsonReferenceNode.is(subNode)) {

                    JsonNode replacementNode = getReplacementNode(subNode, context);
                    if (replacementNode == null) {
                        logger.info("Got null replacement node on position " + i);
                        continue;
                    }
                    logger.debug("replacing " + "subNode" + " with " + replacementNode);
                    arrayNode.set(i, replacementNode);
                   
                } else {
                    process(context, subNode);
                }
                 ++i;
            }
        } else if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;

            Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey();
                JsonNode subNode = field.getValue();

                logger.trace("key=" + key);

                if (JsonReferenceNode.is(subNode)) {

                    JsonNode replacementNode = getReplacementNode(subNode, context);
                    if (replacementNode == null) {
                        logger.info("Got null replacement node for key " + key);
                        continue;
                    }
                    logger.debug("replacing " + "subNode" + " with " + replacementNode);
                    objectNode.set(key, replacementNode);
                } else {
                    process(context, subNode);
                }
            }
        }
        return node;
    }

    public JsonNode getReplacementNode(JsonNode node, JsonContext context) throws JsonReferenceException, IOException {

        JsonReferenceNode refNode = JsonReferenceNode.fromNode(node);
        JsonReference ref = refNode.getJsonReference();
        JsonReference absRef = getAbsoluteRef(ref, context);
        Set<JsonReference> processed = context.getProcessed();

        if((preserveLocalRefs && ref.isLocal()) || (preserveExternalRefs && !ref.isLocal())) {
            return node;
        }

        if (stopOnCircular && processed.contains(absRef)) {
            logger.debug("skipping on ref: " + absRef);
            return null;
        }
        // add ref to processed set for detection loop in recursion
        processed.add(absRef);

        JsonContext referencedContext = resolveFromContextToContext(ref, context);

        // recurse, throw away referencedContext because it could be replaced completely
        JsonNode replacementNode = process(referencedContext, processed);
        // after recursing, remove ref from processed set for next iteration
        processed.remove(absRef);

        if (preserveRefs && replacementNode.isObject()) {
            ((ObjectNode) replacementNode).replace(refPrefix, new TextNode(ref.toString()));
        }
        return replacementNode;
    }

    public JsonReference getAbsoluteRef(JsonReference ref, JsonContext context) throws JsonReferenceException {
        String newRefString = context.getUrl().toString().split("#")[0] + "#" + ref.getPointer().toString();
        JsonReference newRef = JsonReference.fromString(newRefString);
        return newRef;
    }

    public JsonContext resolveFromContextToContext(JsonReference ref, JsonContext context) throws IOException, JsonReferenceException {

        JsonContext referencedContext;
        JsonNode referencedNode;

        URL absoluteReferencedUrl;
        URI refUri = ref.getUri();

        logger.debug("dereferencing " + ref);

        if (ref.isLocal()) {
            absoluteReferencedUrl = context.getUrl();
            referencedNode = context.at(ref.getPointer());
        } else if (ref.isAbsolute()) {
            absoluteReferencedUrl = refUri.toURL();
            referencedNode = read(absoluteReferencedUrl).at(ref.getPointer());
        } else {
            URL contextUrl = context.getUrl();
            try {
                absoluteReferencedUrl = contextUrl.toURI().resolve(refUri).toURL();
            } catch (URISyntaxException e) {
                throw new JsonReferenceException("Invalid URI for context URL: " + contextUrl);
            }
            referencedNode = read(absoluteReferencedUrl).at(ref.getPointer());
        }

        referencedContext = new JsonContext();
        referencedContext.setUrl(absoluteReferencedUrl);
        referencedContext.setNode(referencedNode);
        referencedContext.setFactory(someMapperFactory());
        referencedContext.setProcessed(new HashSet<JsonReference>(context.getProcessed()));

        return referencedContext;
    }

    /**
     * Resolve with defaults.
     * - Assumes ref points to absolute URL.
     *
     * @param ref ref to absolute URL
     * @return node node
     * @throws IOException on invalid ref (URL or file)
     * @throws JsonReferenceException on invalid ref
     */
    public JsonNode get(JsonReference ref) throws IOException, JsonReferenceException {
        JsonNode referencedNode;

        URI refUri = ref.getUri();
        URL url = refUri.toURL();

        referencedNode = read(url).at(ref.getPointer());

        return referencedNode;
    }

    private ConcurrentHashMap<Object, JsonNode> cache = new ConcurrentHashMap<>(1);

    public JsonNode read(URL url) throws IOException, JsonReferenceException {
        putIntoCache(url);
        return cache.get(url);
    }

    public JsonNode read(File file) throws IOException, JsonReferenceException {
        putIntoCache(file);
        return cache.get(file);
    }

    // can only be an URL or a File
    private void putIntoCache(Object any) throws IOException, JsonReferenceException {
        if (any == null) {
            throw new JsonReferenceException("Cannot cache null object.");
        }
        if (cacheInMemory && !cache.contains(any)) {
            logger.debug("Putting into the cache: " + any);
            URL url;
            if (any instanceof URL) {
                url = (URL) any;
            }
            else if (any instanceof File) {
                url = ((File) any).toURI().toURL();
            }
            else {
                throw new JsonReferenceException("Unknown instance type for cache: " + any.getClass());
            }
            ObjectMapper mapper = someMapperFactory().create(url);
            JsonNode tree = mapper.readTree(url);
            if (tree == null) {
                throw new JsonReferenceException("Cannot cache null mapped object.");
            }
            cache.putIfAbsent(any, tree);
        }
    }

    public JsonNode readFile(String fileString) throws IOException, JsonReferenceException {
        File file = new File(fileString);
        return read(file);
    }

}
