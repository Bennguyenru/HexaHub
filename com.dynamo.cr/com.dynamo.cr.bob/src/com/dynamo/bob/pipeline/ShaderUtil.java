// Copyright 2020-2022 The Defold Foundation
// Copyright 2014-2020 King
// Copyright 2009-2014 Ragnar Svensson, Christian Murray
// Licensed under the Defold License version 1.0 (the "License"); you may not use
// this file except in compliance with the License.
// 
// You may obtain a copy of the License, together with FAQs at
// https://www.defold.com/license
// 
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.

package com.dynamo.bob.pipeline;

import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Scanner;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.node.ArrayNode;
import org.codehaus.jackson.map.ObjectMapper;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.ArrayUtils;

import com.dynamo.bob.CompileExceptionError;
import com.dynamo.bob.fs.IResource;


public class ShaderUtil {

    public static class Common {
        private static final String  regexCommentRemovePattern = "(?:/\\*(?:[^*]|(?:\\*+[^*/]))*\\*+/)|(?://.*)"; // Ref http://blog.ostermiller.org/find-comment
        public static String stripComments(String source)
        {
            return source.replaceAll(regexCommentRemovePattern,"");
        }
    }

    public static class IncludeDirectiveCompiler {

        private static String shaderSource;
        private static String includeDirectiveStr = "#include\\s+\"(?<path>.+)\"";
        private static Pattern includeDirectivePattern = Pattern.compile(includeDirectiveStr);

        public static final String RootKey = "INCLUDE_ROOT";

        public static class IncludeNode
        {
            public String path;
            public HashMap<String, IncludeNode> children = new HashMap<String, IncludeNode>();
        };

        public static class Mapping {
            public String path;
            public String data;
        }

        public static class Result extends ArrayList<Mapping> {}

        public IncludeDirectiveCompiler(String source) {
            this.shaderSource = source;
        }

        public static IncludeNode BuildGraph() {
            return BuildShaderIncludeGraph(shaderSource, RootKey);
        }

        public static Result Compile(IncludeNode root)
        {
            Result res = new Result();
            for (Map.Entry<String, IncludeNode> child : root.children.entrySet()) {
                String key        = child.getKey();
                IncludeNode value = child.getValue();
                CompileNode(value, res);
            }

            return res;
        }

        private static void CompileNode(IncludeNode node, ArrayList<Mapping> mappings)
        {
            for (Map.Entry<String, IncludeNode> child : node.children.entrySet()) {
                String key           = child.getKey();
                IncludeNode value    = child.getValue();
                CompileNode(value, mappings);
            }

            Mapping child_mapping = new Mapping();
            child_mapping.path = node.path;
            mappings.add(child_mapping);
        }

        private static IncludeNode BuildShaderIncludeGraph(String fromData, String fromPath)
        {
            IncludeNode new_node = new IncludeNode();
            new_node.path = fromPath;

            Matcher includeDirectiveMatcher = includeDirectivePattern.matcher(fromData);
            while (includeDirectiveMatcher.find()) {
                String shaderIncludePath = includeDirectiveMatcher.group("path");

                if (shaderIncludePath != null)
                {
                    String childData = null;
                    try(FileInputStream inputStream = new FileInputStream(shaderIncludePath)) {     
                        childData = IOUtils.toString(inputStream);
                    } catch (Exception e) {
                        System.out.println("Some kind of error");
                    }

                    if (childData != null)
                    {
                        IncludeNode childNode = BuildShaderIncludeGraph(childData, shaderIncludePath);
                        new_node.children.put(shaderIncludePath, childNode);
                    }
                }
            }

            return new_node;
        }

        /*
        public static void PrintGraph(IncludeNode node, int depth)
        {
            if (node == null)
            {
                return;
            }

            String prefix = "";
            for (int i=0; i < depth; i++) {
                prefix += "  ";
            }

            System.out.println(prefix + "Node " + node.path);
            for (Map.Entry<String, IncludeNode> child : node.children.entrySet()) {
                String key        = child.getKey();
                IncludeNode value = child.getValue();
                System.out.println(prefix + "  Child " + key);
                PrintGraph(value, depth + 1);
            }
        }
        */

        private static void CompileIncludeDirective(IncludeNode node, Mapping[] pathToDataMapping, ArrayList<String> includeData)
        {
            for (Map.Entry<String, IncludeNode> child : node.children.entrySet()) {
                String key           = child.getKey();
                IncludeNode value    = child.getValue();
                CompileIncludeDirective(value, pathToDataMapping, includeData);
            }

            Mapping found_mapping = null;
            for (Mapping mapping : pathToDataMapping) {
                if (mapping.path.equals(node.path))
                {
                    found_mapping = mapping;
                    break;
                }
            }

            if (found_mapping != null) {
                includeData.add(found_mapping.data);
            } else {
                System.out.println("Could not find include node for " + node.path);
            }
        }

        public static String InsertIncludeDirective(String source, IncludeNode node, Mapping[] pathToDataMapping)
        {
            for (Map.Entry<String, IncludeNode> child : node.children.entrySet()) {
                String includeNodePatternStr = String.format("#include\\s+\"%s\"", child.getKey());
                Pattern includeNodePattern = Pattern.compile(includeNodePatternStr);

                ArrayList<String> stringBuffer = new ArrayList<String>();
                CompileIncludeDirective(child.getValue(), pathToDataMapping, stringBuffer);

                String compiledNode = String.join("\n", stringBuffer);
                source = source.replaceAll(includeNodePatternStr, compiledNode);
            }

            return source;
        }

        public static Mapping[] GetMappingFromResources(List<IResource> includeResources) throws IOException
        {
            Mapping[] includes = new Mapping[includeResources.size()];

            for (int i=0; i < includeResources.size(); i++) {
                IResource include_file = includeResources.get(i);
                try (ByteArrayInputStream is = new ByteArrayInputStream(include_file.getContent())) {

                    int n = is.available();
                    byte[] bytes = new byte[n];
                    is.read(bytes, 0, n);
                    String include_source = new String(bytes, StandardCharsets.UTF_8);

                    // Strip all include directives
                    include_source = include_source.replaceAll(includeDirectiveStr, "");

                    Mapping include_map_entry = new Mapping();
                    include_map_entry.path = include_file.getPath();
                    include_map_entry.data = include_source;

                    includes[i] = include_map_entry;
                }   
            }
            return includes;
        }
    }

    public static class SPIRVReflector {
        private static JsonNode root;

        public SPIRVReflector(String json) throws IOException
        {
            this.root = (new ObjectMapper()).readTree(json);
        }

        public static class Resource
        {
            public String name;
            public String type;
            public int    elementCount;
            public int    binding;
            public int    set;
        }

        public static class UniformBlock extends Resource
        {
            public ArrayList<Resource> uniforms;
        }

        public static ArrayList<UniformBlock> getUniformBlocks()
        {
            ArrayList<UniformBlock> uniformBlocks = new ArrayList<UniformBlock>();

            JsonNode uboNode   = root.get("ubos");
            JsonNode typesNode = root.get("types");

            if (uboNode == null || typesNode == null) {
                return uniformBlocks;
            }

            Iterator<JsonNode> uniformBlockNodeIt = uboNode.getElements();
            while (uniformBlockNodeIt.hasNext()) {
                JsonNode uniformBlockNode = uniformBlockNodeIt.next();

                UniformBlock ubo = new UniformBlock();
                ubo.name         = uniformBlockNode.get("name").asText();
                ubo.set          = uniformBlockNode.get("set").asInt();
                ubo.binding      = uniformBlockNode.get("binding").asInt();
                ubo.uniforms     = new ArrayList<Resource>();

                JsonNode typeNode    = typesNode.get(uniformBlockNode.get("type").asText());
                JsonNode membersNode = typeNode.get("members");

                for (Iterator<JsonNode> membersNodeIt = membersNode.getElements(); membersNodeIt.hasNext();) {
                    JsonNode uniformNode = membersNodeIt.next();
                    Resource res         = new Resource();
                    res.name             = uniformNode.get("name").asText();
                    res.type             = uniformNode.get("type").asText();
                    res.elementCount     = 1;
                    res.binding          = 0;
                    res.set              = 0;

                    JsonNode arrayNode = uniformNode.get("array");
                    if (arrayNode != null && arrayNode.isArray())
                    {
                        ArrayNode array = (ArrayNode) arrayNode;
                        res.elementCount = arrayNode.get(0).asInt();
                    }

                    ubo.uniforms.add(res);
                }

                uniformBlocks.add(ubo);
            }

            return uniformBlocks;
        }

        public static ArrayList<Resource> getTextures() {
            ArrayList<Resource> textures = new ArrayList<Resource>();

            JsonNode texturesNode = root.get("textures");

            if (texturesNode == null) {
                return textures;
            }

            for (Iterator<JsonNode> iter = texturesNode.getElements(); iter.hasNext();) {
                JsonNode textureNode = iter.next();
                Resource res     = new Resource();
                res.name         = textureNode.get("name").asText();
                res.type         = textureNode.get("type").asText();
                res.binding      = textureNode.get("binding").asInt();
                res.set          = textureNode.get("set").asInt();
                res.elementCount = 1;
                textures.add(res);
            }

            return textures;
        }

        public static ArrayList<Resource> getInputs() {
            ArrayList<Resource> inputs = new ArrayList<Resource>();

            JsonNode inputsNode = root.get("inputs");

            if (inputsNode == null) {
                return inputs;
            }

            for (Iterator<JsonNode> iter = inputsNode.getElements(); iter.hasNext();) {
                JsonNode inputNode = iter.next();
                Resource res = new Resource();
                res.name     = inputNode.get("name").asText();
                res.type     = inputNode.get("type").asText();
                res.binding  = inputNode.get("location").asInt();
                inputs.add(res);
            }

            return inputs;
        }
    }

    public static class ES2ToES3Converter {

        /*
         * ES2ToES3Converter is converting shaders using old shader language syntax into GLES version 310 compliant shaders or GL version 140 compliant shaders depending on target (GLES or GL)
         *
         * The following rules apply
         *
         * * Shader version and profile (#version xxx xx) is overridden if declared in the shader (default is minimum per target, see above)
         *
         * Vertex Shaders:
         * * "attribute" keyword is changed to "in"
         * * "varying" keyword is changed to "out"
         * * Uniform declarations are wrapped to global space uniform buffers
         *   - Unless the type is opaque (sampler, image or atomic_uint)
         *
         * Fragment Shaders:
         * * "varying" keyword is changed to "out"
         * * Uniform declarations are wrapped to global space uniform buffers
         *   - Unless the type is opaque (sampler, image or atomic_uint)
         * * Precision mediump float is added to ES shaders if not existing
         * * If exists, gl_FragColor or gl_FragData are converted to a generated out attribute.
         *   - If they exist (otherwise, one can assume this is already a compliant shader)
         *   - On ES targets only (GL targets accepts old style)
         *   - Placed after the first precision statement (if ES)
         *
         * Note: This covers known cases, but if the shader has reserved variable names of (newer) keywords as "in", "out" or "texture" they will have to be modified by the writer.
         * This would have to be done in any case upgrading shaders from GLES2 and is nothing we can patch as changing those members names would instead mean the run-time access (get/set uniform)
         * will fail which is even worse.
         *
         */

        public static class Result {
            public String shaderVersion = "";
            public String shaderProfile = "";
            public String output = "";
        }

        public static enum ShaderType {
            VERTEX_SHADER, FRAGMENT_SHADER
        };

        private static final String[] opaqueUniformTypesPrefix = { "sampler", "image", "atomic_uint" };
        private static final Pattern regexLineBreakPattern = Pattern.compile("(?<=;)|(?<=\\{)|(?<=\\})|(?<=(#(.{0,1024}\\n)))");
        private static final Pattern regexVersionStringPattern = Pattern.compile("^\\h*#\\h*version\\h+(?<version>\\d+)(\\h+(?<profile>\\S+))?\\h*\\n");
        private static final Pattern regexPrecisionKeywordPattern = Pattern.compile("(?<keyword>precision)\\s+(?<precision>lowp|mediump|highp)\\s+(?<type>float|int)\\s*;");
        private static final Pattern regexUniformKeywordPattern = Pattern.compile("((?<keyword>uniform)\\s+|(?<layout>layout\\s*\\(.*\\n*.*\\)\\s*)\\s+|(?<precision>lowp|mediump|highp)\\s+)*(?<type>\\S+)\\s+(?<identifier>\\S+)\\s*(?<any>.*)\\s*;");
        private static final Pattern regexFragDataArrayPattern = Pattern.compile("gl_FragData\\[(?<index>\\d+)\\]");

        private static final String[][] vsKeywordReps = {{"varying", "out"}, {"attribute", "in"}, {"texture2D", "texture"}, {"textureCube", "texture"}};
        private static final String[][] fsKeywordReps = {{"varying", "in"}, {"texture2D", "texture"}, {"textureCube", "texture"}};

        private static final String dmEngineGeneratedRep = "_DMENGINE_GENERATED_";

        private static final String glUBRep = dmEngineGeneratedRep + "UB_";
        private static final String glFragColorKeyword = "gl_FragColor";
        private static final String glFragDataKeyword = "gl_FragData";
        private static final String glFragColorRep = dmEngineGeneratedRep + glFragColorKeyword;
        private static final String glFragColorAttrRep = "\nout vec4 " + glFragColorRep + "%s;\n";
        private static final String floatPrecisionAttrRep = "precision mediump float;\n";

        public static Result transform(String input, ShaderType shaderType, String targetProfile, int targetVersion, boolean useLatestFeatures) throws CompileExceptionError {
            Result result = new Result();

            // Remove comments, early bail if zero code
            // input = input.replaceAll(regexCommentRemovePattern,"");
            input = Common.stripComments(input);

            if(input.isEmpty()) {
                return result;
            }

            int layoutSet = shaderType == ShaderType.VERTEX_SHADER ? 0 : 1;

            // Index to output used for post patching tasks
            int floatPrecisionIndex = -1;

            // Try get version and profile. Override targetProfile if version is set in shader
            Matcher versionMatcher = regexVersionStringPattern.matcher(input.substring(0, Math.min(input.length(), 128)));
            if (versionMatcher.find()) {
                result.shaderVersion = versionMatcher.group("version");
                result.shaderProfile = versionMatcher.group("profile");
                result.shaderProfile = result.shaderProfile == null ? "" : result.shaderProfile;
                // override targetProfile if version is set in shader
                targetProfile = result.shaderProfile;
            } else {
                String versionPrefix = String.format("#version %d", targetVersion);
                if (!targetProfile.isEmpty())
                    versionPrefix += String.format(" %s", targetProfile);
                versionPrefix += "\n";
                input = versionPrefix + input;
            }

            if (!result.shaderVersion.isEmpty()) {
                targetVersion = Integer.parseInt(result.shaderVersion);
            }

            // Patch qualifiers (reserved keywords so word boundary replacement is safe)
            String[][] keywordReps = (shaderType == ShaderType.VERTEX_SHADER) ? vsKeywordReps : fsKeywordReps;
            for ( String[] keywordRep : keywordReps) {
                input = input.replaceAll("\\b" + keywordRep[0] + "\\b", keywordRep[1]);
            }

            // Get data output array size
            Matcher fragDataArrayMatcher = regexFragDataArrayPattern.matcher(input);
            int maxColorOutputs = 1;
            while (fragDataArrayMatcher.find()) {
                String fragDataArrayIndex = fragDataArrayMatcher.group("index");
                maxColorOutputs = Math.max(maxColorOutputs, Integer.parseInt(fragDataArrayIndex) + 1);
            }

            // Replace fragment output variables 
            boolean output_glFragColor = input.contains(glFragColorKeyword);
            boolean output_glFragData = input.contains(glFragDataKeyword);

            if (output_glFragColor)
            {
                input = input.replaceAll("\\b" + glFragColorKeyword + "\\b", glFragColorRep + "_0");
            }

            if (output_glFragData)
            {
                input = input.replaceAll("\\b" + glFragDataKeyword + "\\[(\\d+)\\]", glFragColorRep + "_$1");
            }

            // Split into slices separated by semicolon, curly bracket scopes and preprocessor definition lines: ";", "{", "}" and "#..<\n>"
            // This to reduce parsing complexity
            String[] inputLines = regexLineBreakPattern.split(input);

            // Find the first non directive line
            Pattern directiveLinePattern = Pattern.compile("^\\s*(#|//).*");
            int patchLineIndex = 0;
            for(String line : inputLines) {
                line = line.trim();
                if (line.startsWith("#line")) { // The next line is where the used code starts
                    break;
                }
                if (line.isEmpty() || directiveLinePattern.matcher(line).find()) {
                    patchLineIndex++;
                    continue;
                }
                break;
            }

            // Preallocate array of resulting slices. This makes patching in specific positions less complex
            ArrayList<String> output = new ArrayList<String>(input.length());

            // Multi-instance patching
            int ubIndex = 0;
            for(String line : inputLines) {

                if(line.contains("uniform") && !line.contains("{") && useLatestFeatures)
                {
                    // Transform non-opaque uniforms into uniform blocks (UB's). Do not process existing UB's
                    Matcher uniformMatcher = regexUniformKeywordPattern.matcher(line);
                    if(uniformMatcher.find()) {
                        String keyword = uniformMatcher.group("keyword");
                        if(keyword != null) {
                            String layout = uniformMatcher.group("layout");
                            String precision = uniformMatcher.group("precision");
                            String type = uniformMatcher.group("type");
                            String identifier = uniformMatcher.group("identifier");
                            String any = uniformMatcher.group("any");

                            boolean isOpaque = false;
                            for( String opaqueTypePrefix : opaqueUniformTypesPrefix) {
                                if(type.startsWith(opaqueTypePrefix)) {
                                    isOpaque = true;
                                    break;
                                }
                            }

                            if (layout == null) {
                                layout = "layout(set=" + layoutSet + ")";
                            }

                            if (isOpaque){
                                line = layout + " " + line;
                            } else {
                                line = "\n" + layout + " " + keyword + " " + glUBRep + ubIndex++ + " { " +
                                (precision == null ? "" : (precision + " ")) + type + " " + identifier + " " + (any == null ? "" : (any + " ")) + "; };";
                            }
                        }
                    }
                }
                else if (line.contains("precision")) {
                    // Check if precision keyword present and store index if so, for post patch tasks
                    Matcher precisionMatcher = regexPrecisionKeywordPattern.matcher(line);
                    if(precisionMatcher.find()) {
                        if(precisionMatcher.group("type").equals("float")) {
                            floatPrecisionIndex = output.size();
                        }
                    }
                }
                output.add(line);
            }

            // Post patching
            if (shaderType == ShaderType.FRAGMENT_SHADER) {
                // if we have patched glFragColor
                if(output_glFragColor || output_glFragData) {
                    // insert precision if not found, as it is mandatory for out attributes
                    if(floatPrecisionIndex < 0 && targetProfile.equals("es")) {
                        output.add(patchLineIndex++, floatPrecisionAttrRep);
                    }
                    for (int i = 0; i < maxColorOutputs; i++) {
                         // insert fragcolor out attr
                        output.add(patchLineIndex++, String.format(glFragColorAttrRep, "_" + i));   
                    }
                }
            }

            result.output = String.join("", output);
            return result;
        }
    }

}
