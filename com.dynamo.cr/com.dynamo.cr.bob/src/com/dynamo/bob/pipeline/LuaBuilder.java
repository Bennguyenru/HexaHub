package com.dynamo.bob.pipeline;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.vecmath.Quat4d;
import javax.vecmath.Vector3d;
import javax.vecmath.Vector4d;

import org.apache.commons.io.IOUtils;

import com.dynamo.bob.Bob;
import com.dynamo.bob.Builder;
import com.dynamo.bob.BuilderParams;
import com.dynamo.bob.CompileExceptionError;
import com.dynamo.bob.Platform;
import com.dynamo.bob.Task;
import com.dynamo.bob.fs.IResource;
import com.dynamo.bob.pipeline.LuaScanner.Property.Status;
import com.dynamo.bob.util.MurmurHash;
import com.dynamo.lua.proto.Lua.LuaModule;
import com.dynamo.properties.proto.PropertiesProto.PropertyDeclarationEntry;
import com.dynamo.properties.proto.PropertiesProto.PropertyDeclarations;
import com.dynamo.script.proto.Lua.LuaSource;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;

/**
 * Lua builder. This class is abstract. Inherit from this class
 * and add appropriate {@link BuilderParams}
 * @author chmu
 *
 */
public abstract class LuaBuilder extends Builder<Void> {

    @Override
    public Task<Void> create(IResource input) throws IOException, CompileExceptionError {
        Task<Void> task = Task.<Void>newBuilder(this)
                .setName(params.name())
                .addInput(input)
                .addOutput(input.changeExt(params.outExt()))
                .build();
        return task;
    }

    public byte[] constructBytecode(Task<Void> task) throws IOException, CompileExceptionError {

        java.io.FileOutputStream fo = null;
        RandomAccessFile rdr = null;

        try {

            File outputFile = File.createTempFile("script", ".raw");
            File inputFile = File.createTempFile("script", ".lua");

            // Need to write the input file separately in case it comes from built-in, and cannot
            // be found through its path alone.
            fo = new java.io.FileOutputStream(inputFile);
            fo.write(task.input(0).getContent());
            fo.close();

            // Doing a bit of custom set up here as the path is required.
            //
            // NOTE: The -f option for bytecode is a small custom modification to bcsave.lua in LuaJIT which allows us to supply the
            //       correct chunk name (the original original source file) already here.
            //
            // See implementation of luaO_chunkid and why a prefix '=' is used; it is to pass through the filename without modifications.
            ProcessBuilder pb = new ProcessBuilder(new String[] { Bob.getExe(Platform.getHostPlatform(), "luajit"), "-bgf", ("=" + task.input(0).getPath()), inputFile.getAbsolutePath(), outputFile.getAbsolutePath() }).redirectErrorStream(true);

            java.util.Map<String, String> env = pb.environment();
            env.put("LUA_PATH", Bob.getPath("share/luajit/") + "/?.lua");

            Process p = pb.start();
            InputStream is = null;
            int ret = 127;

            try {
                ret = p.waitFor();
                is = p.getInputStream();

                int toRead = is.available();
                byte[] buf = new byte[toRead];
                is.read(buf);

                String cmdOutput = new String(buf);
                if (ret != 0) {
                    // first delimiter is the executable name "luajit:"
                    int execSep = cmdOutput.indexOf(':');
                    if (execSep > 0) {
                        // then comes the filename and the line like this:
                        // "file.lua:30: <error message>"
                        int lineBegin = cmdOutput.indexOf(':', execSep + 1);
                        if (lineBegin > 0) {
                            int lineEnd = cmdOutput.indexOf(':', lineBegin + 1);
                            if (lineEnd > 0) {
                                throw new CompileExceptionError(task.input(0),
                                        Integer.parseInt(cmdOutput.substring(
                                                lineBegin + 1, lineEnd)),
                                        cmdOutput.substring(lineEnd + 2));
                            }
                        }
                    }
                    // Since parsing out the actual error failed, as a backup just
                    // spit out whatever jualit said.
                    inputFile.delete();
                    throw new CompileExceptionError(task.input(0), 1, cmdOutput);
                }
            } catch (InterruptedException e) {
                Logger.getLogger(LuaBuilder.class.getCanonicalName()).log(Level.SEVERE, "Unexpected interruption", e);
            } finally {
                IOUtils.closeQuietly(is);
            }

            long resultBytes = outputFile.length();
            rdr = new RandomAccessFile(outputFile, "r");
            byte tmp[] = new byte[(int) resultBytes];
            rdr.readFully(tmp);

            outputFile.delete();
            inputFile.delete();
            return tmp;
        }
        finally {
            IOUtils.closeQuietly(fo);
            IOUtils.closeQuietly(rdr);
        }
    }

    @Override
    public void build(Task<Void> task) throws CompileExceptionError, IOException {

        LuaModule.Builder builder = LuaModule.newBuilder();
        byte[] scriptBytes = task.input(0).getContent();
        String script = new String(scriptBytes);
        List<String> modules = LuaScanner.scan(script);

        for (String module : modules) {
            String module_file = String.format("/%s.lua", module.replaceAll("\\.", "/"));
            BuilderUtil.checkResource(this.project, task.input(0), "module", module_file);
            builder.addModules(module);
            builder.addResources(module_file + "c");
        }
        List<LuaScanner.Property> properties = LuaScanner.scanProperties(script);
        PropertyDeclarations propertiesMsg = buildProperties(task.input(0), properties);
        builder.setProperties(propertiesMsg);

        LuaSource.Builder srcBuilder = LuaSource.newBuilder();

        srcBuilder.setScript(ByteString.copyFrom(scriptBytes));
        srcBuilder.setFilename(task.input(0).getPath());

        // For now it will always return, or throw an exception. This leaves the possibility of
        // disabling bytecode generation.
        byte[] bytecode = constructBytecode(task);
        if (bytecode != null)
            srcBuilder.setBytecode(ByteString.copyFrom(bytecode));

        builder.setSource(srcBuilder);

        Message msg = builder.build();
        ByteArrayOutputStream out = new ByteArrayOutputStream(4 * 1024);
        msg.writeTo(out);
        out.close();
        task.output(0).setContent(out.toByteArray());


    }

    private PropertyDeclarations buildProperties(IResource resource, List<LuaScanner.Property> properties) throws CompileExceptionError {
        PropertyDeclarations.Builder builder = PropertyDeclarations.newBuilder();
        if (!properties.isEmpty()) {
            for (LuaScanner.Property property : properties) {
                if (property.status == Status.OK) {
                    PropertyDeclarationEntry.Builder entryBuilder = PropertyDeclarationEntry.newBuilder();
                    entryBuilder.setKey(property.name);
                    entryBuilder.setId(MurmurHash.hash64(property.name));
                    switch (property.type) {
                    case PROPERTY_TYPE_NUMBER:
                        entryBuilder.setIndex(builder.getFloatValuesCount());
                        builder.addFloatValues(((Double)property.value).floatValue());
                        builder.addNumberEntries(entryBuilder);
                        break;
                    case PROPERTY_TYPE_HASH:
                        entryBuilder.setIndex(builder.getHashValuesCount());
                        builder.addHashValues(MurmurHash.hash64((String)property.value));
                        builder.addHashEntries(entryBuilder);
                        break;
                    case PROPERTY_TYPE_URL:
                        entryBuilder.setIndex(builder.getStringValuesCount());
                        builder.addStringValues((String)property.value);
                        builder.addUrlEntries(entryBuilder);
                        break;
                    case PROPERTY_TYPE_VECTOR3:
                        entryBuilder.setIndex(builder.getFloatValuesCount());
                        Vector3d v3 = (Vector3d)property.value;
                        builder.addFloatValues((float)v3.getX());
                        builder.addFloatValues((float)v3.getY());
                        builder.addFloatValues((float)v3.getZ());
                        builder.addVector3Entries(entryBuilder);
                        break;
                    case PROPERTY_TYPE_VECTOR4:
                        entryBuilder.setIndex(builder.getFloatValuesCount());
                        Vector4d v4 = (Vector4d)property.value;
                        builder.addFloatValues((float)v4.getX());
                        builder.addFloatValues((float)v4.getY());
                        builder.addFloatValues((float)v4.getZ());
                        builder.addFloatValues((float)v4.getW());
                        builder.addVector4Entries(entryBuilder);
                        break;
                    case PROPERTY_TYPE_QUAT:
                        entryBuilder.setIndex(builder.getFloatValuesCount());
                        Quat4d q = (Quat4d)property.value;
                        builder.addFloatValues((float)q.getX());
                        builder.addFloatValues((float)q.getY());
                        builder.addFloatValues((float)q.getZ());
                        builder.addFloatValues((float)q.getW());
                        builder.addQuatEntries(entryBuilder);
                        break;
                    case PROPERTY_TYPE_BOOLEAN:
                        entryBuilder.setIndex(builder.getFloatValuesCount());
                        builder.addFloatValues(((Boolean)property.value) ? 1.0f : 0.0f);
                        builder.addBoolEntries(entryBuilder);
                        break;
                    }
                } else if (property.status == Status.INVALID_ARGS) {
                    throw new CompileExceptionError(resource, property.line + 1, "go.property takes a string and a value as arguments. The value must have the type number, boolean, hash, msg.url, vmath.vector3, vmath.vector4 or vmath.quat.");
                } else if (property.status == Status.INVALID_VALUE) {
                    throw new CompileExceptionError(resource, property.line + 1, "Only these types are available: number, hash, msg.url, vmath.vector3, vmath.vector4, vmath.quat");
                }
            }
        }
        return builder.build();
    }
}

