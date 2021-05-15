package com.jozufozu.flywheel.backend;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.lwjgl.system.MemoryUtil;

import com.google.common.collect.Lists;
import com.jozufozu.flywheel.backend.gl.GlObject;
import com.jozufozu.flywheel.backend.gl.shader.GlProgram;
import com.jozufozu.flywheel.backend.gl.shader.GlShader;
import com.jozufozu.flywheel.backend.gl.shader.ShaderType;
import com.jozufozu.flywheel.backend.loading.Shader;
import com.jozufozu.flywheel.backend.loading.ShaderTransformer;
import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.resources.IResource;
import net.minecraft.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.resource.IResourceType;
import net.minecraftforge.resource.VanillaResourceType;

public class ShaderLoader {
	public static final String SHADER_DIR = "flywheel/shaders/";
	public static final ArrayList<String> EXTENSIONS = Lists.newArrayList(".vert", ".vsh", ".frag", ".fsh", ".glsl");

	// #flwinclude <"valid_namespace:valid/path_to_file.glsl">
	private static final Pattern includePattern = Pattern.compile("#flwinclude <\"([\\w\\d_]+:[\\w\\d_./]+)\">");
	private static boolean debugDumpFile = true;

	private final Map<ResourceLocation, String> shaderSource = new HashMap<>();

	void onResourceManagerReload(IResourceManager manager, Predicate<IResourceType> predicate) {
		if (predicate.test(VanillaResourceType.SHADERS)) {
			OptifineHandler.refresh();
			Backend.refresh();

			if (Backend.gl20()) {
				shaderSource.clear();
				loadShaderSources(manager);

//				InstancedArraysTemplate template = new InstancedArraysTemplate(this);
//
//				ResourceLocation name = new ResourceLocation("create", "test");
//				ResourceLocation vert = new ResourceLocation("create", "model_new.vert");
//				ResourceLocation frag = new ResourceLocation("create", "block_new.frag");
//
//				ShaderTransformer transformer = new ShaderTransformer()
//						.pushStage(WorldContext.INSTANCE.loadingStage(this))
//						.pushStage(this::processIncludes)
//						.pushStage(template)
//						.pushStage(this::processIncludes);
//
//				Shader vertexFile = this.source(vert, ShaderType.VERTEX);
//				Shader fragmentFile = this.source(frag, ShaderType.FRAGMENT);
//
//				GlProgram.Builder builder = loadProgram(name, transformer, vertexFile, fragmentFile);
//
//				BasicProgram program = new BasicProgram(builder, GlFogMode.NONE.getFogFactory());

				for (ShaderContext<?> context : Backend.contexts.values()) {
					context.load(this);
				}

				Backend.log.info("Loaded all shader programs.");

				// no need to hog all that memory
				shaderSource.clear();
			}
		}
	}

	public String getShaderSource(ResourceLocation loc) {
		return shaderSource.get(loc);
	}

	private void loadShaderSources(IResourceManager manager) {
		Collection<ResourceLocation> allShaders = manager.getAllResourceLocations(SHADER_DIR, s -> {
			for (String ext : EXTENSIONS) {
				if (s.endsWith(ext)) return true;
			}
			return false;
		});

		for (ResourceLocation location : allShaders) {
			try {
				IResource resource = manager.getResource(location);

				String file = readToString(resource.getInputStream());

				ResourceLocation name = new ResourceLocation(location.getNamespace(),
						location.getPath().substring(SHADER_DIR.length()));

				shaderSource.put(name, file);
			} catch (IOException e) {

			}
		}
	}

	public Shader source(ResourceLocation name, ShaderType type) {
		return new Shader(type, name, getShaderSource(name));
	}

	public GlProgram.Builder loadProgram(ResourceLocation name, ShaderTransformer transformer, Shader... shaders) {
		return loadProgram(name, transformer, Lists.newArrayList(shaders));
	}

	/**
	 * Ingests the given shaders, compiling them and linking them together after applying the transformer to the source.
	 *
	 * @param name        What should we call this program if something goes wrong?
	 * @param transformer What should we do to the sources before compilation?
	 * @param shaders     What are the different shader stages that should be linked together?
	 * @return A linked program builder.
	 */
	public GlProgram.Builder loadProgram(ResourceLocation name, ShaderTransformer transformer, Collection<Shader> shaders) {
		List<GlShader> compiled = new ArrayList<>(shaders.size());
		try {
			GlProgram.Builder builder = GlProgram.builder(name);

			for (Shader shader : shaders) {
				transformer.transformSource(shader);
				GlShader sh = new GlShader(shader);
				compiled.add(sh);

				builder.attachShader(sh);
			}

			return builder.link();
		} finally {
			compiled.forEach(GlObject::delete);
		}
	}

	private void printSource(ResourceLocation name, String source) {
		Backend.log.debug("Finished processing '" + name + "':");
		int i = 1;
		for (String s : source.split("\n")) {
			Backend.log.debug(String.format("%1$4s: ", i++) + s);
		}
	}

	public void processIncludes(Shader shader) {
		HashSet<ResourceLocation> seen = new HashSet<>();
		seen.add(shader.name);

		String includesInjected = includeRecursive(shader.getSource(), seen).collect(Collectors.joining("\n"));
		shader.setSource(includesInjected);
	}

	private Stream<String> includeRecursive(String source, Set<ResourceLocation> seen) {
		return lines(source).flatMap(line -> {

			Matcher matcher = includePattern.matcher(line);

			if (matcher.find()) {
				String includeName = matcher.group(1);

				ResourceLocation include = new ResourceLocation(includeName);

				if (seen.add(include)) {
					String includeSource = shaderSource.get(include);

					if (includeSource != null) {
						return includeRecursive(includeSource, seen);
					}
				}
			}

			return Stream.of(line);
		});
	}

	public static Stream<String> lines(String s) {
		return new BufferedReader(new StringReader(s)).lines();
	}

	public String readToString(InputStream is) {
		RenderSystem.assertThread(RenderSystem::isOnRenderThread);
		ByteBuffer bytebuffer = null;

		try {
			bytebuffer = readToBuffer(is);
			int i = bytebuffer.position();
			((Buffer) bytebuffer).rewind();
			return MemoryUtil.memASCII(bytebuffer, i);
		} catch (IOException e) {

		} finally {
			if (bytebuffer != null) {
				MemoryUtil.memFree(bytebuffer);
			}

		}

		return null;
	}

	public ByteBuffer readToBuffer(InputStream is) throws IOException {
		ByteBuffer bytebuffer;
		if (is instanceof FileInputStream) {
			FileInputStream fileinputstream = (FileInputStream) is;
			FileChannel filechannel = fileinputstream.getChannel();
			bytebuffer = MemoryUtil.memAlloc((int) filechannel.size() + 1);

			while (filechannel.read(bytebuffer) != -1) {
			}
		} else {
			bytebuffer = MemoryUtil.memAlloc(8192);
			ReadableByteChannel readablebytechannel = Channels.newChannel(is);

			while (readablebytechannel.read(bytebuffer) != -1) {
				if (bytebuffer.remaining() == 0) {
					bytebuffer = MemoryUtil.memRealloc(bytebuffer, bytebuffer.capacity() * 2);
				}
			}
		}

		return bytebuffer;
	}
}