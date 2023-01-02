package com.dynamo.bob.pipeline;

import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.FilenameUtils;

import com.dynamo.bob.CompileExceptionError;
import com.dynamo.bob.Project;
import com.dynamo.bob.fs.IResource;
import com.dynamo.bob.pipeline.ShaderUtil.Common;

/* ShaderIncludeCompiler
 * ========================
 * This class contains functionality to support #include "path-to-file" directives from shader files.
 * During graph creation, the graph will be checked for cycles since we will end up in an endless loop otherwise
 * when building the graph.
 *
 * The process currently:
 *   * A shader program builder creates a graph from a shader file by calling buildGraph(source)
 *   * The builder gets a list of all include paths from the graph and marks these as inputs to the shader resource
 *   * When the task is getting built, the graph is passed to the generator together with a list of path <-> data mapping
 *     so the insertIncludeDirective function can replace all occurances of various #include statements with the include
 *     files corresponding data.
 */
public class ShaderIncludeCompiler {
    // Compiler state
    private String      projectPath;
    private String      shaderSource;
    private String      shaderPath;
    private IncludeNode root;

    private HashMap<String, String> nodeSourceCache;

    private class IncludeNode {
        public String                       path;
        public String                       source;
        public IncludeNode                  parent;
        public HashMap<String, IncludeNode> children = new HashMap<String, IncludeNode>();
    };

    private class IncludeDirectiveGraphIterator implements Iterator<IncludeNode> {
        private IncludeNode            root;
        private ArrayList<IncludeNode> nodeList = new ArrayList<IncludeNode>();
        private int                    nodeIndex;

        public boolean hasNext() {
            return ((nodeIndex + 1 ) < nodeList.size());
        }

        public IncludeNode next() {
            return (nodeList.get(++nodeIndex));
        }

        public void remove() {
            throw new UnsupportedOperationException("Cannot remove a node from include graph");
        }

        private void fillNodeList(IncludeNode node, ArrayList<IncludeNode> nodeList) {
            for (Map.Entry<String, IncludeNode> child : node.children.entrySet()) {
                fillNodeList(child.getValue(), nodeList);
            }
            nodeList.add(node);
        }

        private void reset() {
            this.nodeIndex = -1;
            this.nodeList.clear();
            fillNodeList(this.root, this.nodeList);
        }

        public IncludeDirectiveGraphIterator(IncludeNode node) {
            this.root = node;
            reset();
        }
    }

    public ShaderIncludeCompiler(String projectRoot, String fromPath, String fromSource) throws IOException, CompileExceptionError {
        this.projectPath     = projectRoot;
        this.nodeSourceCache = new HashMap<String, String>();
        this.root            = buildShaderIncludeGraph(null, fromPath, fromSource);
    }

    public String[] getIncludes() {
        ArrayList<String> res = new ArrayList<String>();
        for (Map.Entry<String, IncludeNode> child : this.root.children.entrySet()) {
            IncludeNode val = child.getValue();
            IncludeDirectiveGraphIterator childIterator = new IncludeDirectiveGraphIterator(child.getValue());
            while(childIterator.hasNext()) {
                res.add(childIterator.next().path);
            }
        }
        return res.toArray(new String[0]);
    }

    public String getCompiledSource() throws CompileExceptionError {
        String source = this.root.source;
        for (Map.Entry<String, IncludeNode> child : this.root.children.entrySet()) {

            ArrayList<String> stringBuffer = new ArrayList<String>();

            IncludeDirectiveGraphIterator iterator = new IncludeDirectiveGraphIterator(child.getValue());
            while(iterator.hasNext()) {
                IncludeNode n = iterator.next();
                stringBuffer.add(n.source);
            }

            String compiledNode                       = "\n" + String.join("\n", stringBuffer);
            String includeReplaceIncludeDirectivesStr = String.format(Common.includeDirectiveReplaceBaseStr, ".*");
            String nodePatternReplaceDataStr          = String.format(Common.includeDirectiveReplaceBaseStr, child.getKey());

            compiledNode = compiledNode.replaceAll(includeReplaceIncludeDirectivesStr, "");
            source       = source.replaceAll(nodePatternReplaceDataStr, compiledNode);
        }

        return source;
    }

    private String toProjectRelativePath(String fromFilePath, String includePath) throws CompileExceptionError {

        if (includePath.startsWith("/"))
        {
            return includePath.substring(1);
        }

        if (fromFilePath.startsWith("/"))
        {
            fromFilePath = fromFilePath.substring(1);
        }

        File fromFile            = new File(fromFilePath);
        File file                = new File(fromFile.getParent(), includePath);
        Path filePath            = Paths.get(file.getAbsolutePath());
        Path projectRootPath     = Paths.get(this.projectPath);
        Path projectRelativePath = projectRootPath.relativize(filePath);

        if (projectRelativePath.toString().startsWith(".."))
        {
            throw new CompileExceptionError(fromFilePath + " includes file from outside of project root " + includePath);
        }

        return projectRelativePath.toString();
    }

    private String getOrPutCachedIncludeData(String fromPath) throws FileNotFoundException, IOException
    {
        String childData = null;
        if (this.nodeSourceCache.containsKey(fromPath))
        {
            childData = this.nodeSourceCache.get(fromPath);
        } else {
            File projectFile = new File(this.projectPath, fromPath);
            try(FileInputStream inputStream = new FileInputStream(projectFile)) {
                childData = IOUtils.toString(inputStream);
            }
            this.nodeSourceCache.put(fromPath, childData);
        }
        return childData;
    }

    private IncludeNode buildShaderIncludeGraph(IncludeNode parent, String fromPath, String fromSource) throws IOException, CompileExceptionError {

        IncludeNode newIncludeNode = new IncludeNode();
        newIncludeNode.path        = fromPath;
        newIncludeNode.source      = fromSource;
        newIncludeNode.parent      = parent;

        String[] lines = fromSource.split(System.lineSeparator());
        for (String line : lines) {
            Matcher includeMatcher = Common.includeDirectivePattern.matcher(line);
            if(includeMatcher.find()) {
                String path                = includeMatcher.group("path");
                String projectRelativePath = toProjectRelativePath(fromPath, path);
                String childData           = getOrPutCachedIncludeData(projectRelativePath);
                newIncludeNode.children.put(path, buildShaderIncludeGraph(newIncludeNode, projectRelativePath, childData));
            }
        }

        return newIncludeNode;
    }
}
