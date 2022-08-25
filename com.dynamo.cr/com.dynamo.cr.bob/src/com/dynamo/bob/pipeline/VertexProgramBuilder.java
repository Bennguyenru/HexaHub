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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

import com.dynamo.bob.Bob;
import com.dynamo.bob.BuilderParams;
import com.dynamo.bob.CompileExceptionError;
import com.dynamo.bob.Task;
import com.dynamo.bob.fs.IResource;
import com.dynamo.bob.pipeline.ShaderUtil.ES2ToES3Converter;
import com.dynamo.bob.pipeline.ShaderUtil.IncludeDirectiveCompiler;
import com.dynamo.graphics.proto.Graphics.ShaderDesc;

@BuilderParams(name = "VertexProgram", inExts = ".vp", outExt = ".vpc")
public class VertexProgramBuilder extends ShaderProgramBuilder {

    private static final ES2ToES3Converter.ShaderType SHADER_TYPE = ES2ToES3Converter.ShaderType.VERTEX_SHADER;
    private boolean soft_fail = true;

    @Override
    public void build(Task<IncludeDirectiveCompiler.IncludeNode> task) throws IOException, CompileExceptionError {
        List<IResource> inputs           = task.getInputs();
        List<IResource> includeResources = inputs.subList(1, inputs.size());
        IResource in                     = inputs.get(0);
        IncludeDirectiveCompiler.IncludeNode includeGraph = task.getData();
        try (ByteArrayInputStream is = new ByteArrayInputStream(in.getContent())) {
            boolean isDebug = (project.hasOption("debug") || (project.option("variant", Bob.VARIANT_RELEASE) != Bob.VARIANT_RELEASE));
            boolean outputSpirv = project.getProjectProperties().getBooleanValue("shader", "output_spirv", false);
            ShaderDesc shaderDesc = compile(is,
                includeGraph, IncludeDirectiveCompiler.GetMappingFromResources(includeResources),
                SHADER_TYPE, in, task.getOutputs().get(0).getPath(),
                project.getPlatformStrings()[0], isDebug, outputSpirv, soft_fail);
            task.output(0).setContent(shaderDesc.toByteArray());
        }
    }

    public static void main(String[] args) throws IOException, CompileExceptionError {
        System.setProperty("java.awt.headless", "true");
        VertexProgramBuilder builder = new VertexProgramBuilder();
        builder.soft_fail = false;
        builder.BuildShader(args, SHADER_TYPE);
    }

}
