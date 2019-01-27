/*
 * Copyright 2016 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.loader.entrypoint;

import com.google.common.collect.ImmutableList;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.launch.common.FabricLauncher;
import net.fabricmc.loader.launch.common.FabricLauncherBase;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.io.IOException;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class EntrypointTransformer {
	public static final EntrypointTransformer INSTANCE = new EntrypointTransformer();
	static String appletMainClass;

	private final Logger logger = LogManager.getFormatterLogger("FabricLoader|EntrypointTransformer");
	private Map<String, byte[]> patchedClasses;

	private ClassNode loadClass(FabricLauncher launcher, String className) throws IOException {
		byte[] data = FabricLauncherBase.getLauncher().getClassByteArray(className);
		if (data != null) {
			ClassReader reader = new ClassReader(data);
			ClassNode node = new ClassNode();
			reader.accept(node, 0);
			return node;
		} else {
			return null;
		}
	}

	private void addPatchedClass(ClassNode node) {
		String key = node.name.replace('/', '.');
		if (patchedClasses.containsKey(key)) {
			throw new RuntimeException("Duplicate addPatchedClasses call: " + key);
		}

		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		patchedClasses.put(key, writer.toByteArray());
	}

	private FieldNode findField(ClassNode node, Predicate<FieldNode> predicate) {
		return node.fields.stream().filter(predicate).findAny().orElse(null);
	}

	private List<FieldNode> findFields(ClassNode node, Predicate<FieldNode> predicate) {
		return node.fields.stream().filter(predicate).collect(Collectors.toList());
	}

	private MethodNode findMethod(ClassNode node, Predicate<MethodNode> predicate) {
		return node.methods.stream().filter(predicate).findAny().orElse(null);
	}

	private AbstractInsnNode findInsn(MethodNode node, Predicate<AbstractInsnNode> predicate, boolean last) {
		if (last) {
			for (int i = node.instructions.size() - 1; i >= 0; i--) {
				AbstractInsnNode insn = node.instructions.get(i);
				if (predicate.test(insn)) {
					return insn;
				}
			}
		} else {
			for (int i = 0; i < node.instructions.size(); i++) {
				AbstractInsnNode insn = node.instructions.get(i);
				if (predicate.test(insn)) {
					return insn;
				}
			}
		}

		return null;
	}

	private boolean entrypointsLocated = false;

	private void finishEntrypoint(EnvType type, ListIterator<AbstractInsnNode> it) {
		it.add(new VarInsnNode(Opcodes.ALOAD, 0));
		it.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/fabricmc/loader/entrypoint/Entrypoint" + (type == EnvType.CLIENT ? "Client" : "Server"), "start", "(Ljava/io/File;Ljava/lang/Object;)V", false));
	}

	private void moveAfter(ListIterator<AbstractInsnNode> it, int opcode) {
		while (it.hasNext()) {
			AbstractInsnNode node = it.next();
			if (node.getOpcode() == opcode) {
				break;
			}
		}
	}

	private void moveBefore(ListIterator<AbstractInsnNode> it, int opcode) {
		moveAfter(it, opcode);
		it.previous();
	}

	public void locateEntrypoints(FabricLauncher launcher) {
		if (entrypointsLocated) {
			return;
		}

		entrypointsLocated = true;
		EnvType type = launcher.getEnvironmentType();
		String entrypoint = launcher.getEntrypoint();

		patchedClasses = new HashMap<>(3);

		try {
			String gameEntrypoint = null;
			boolean serverHasFile = true;
			boolean isApplet = entrypoint.contains("Applet");
			ClassNode mainClass = loadClass(launcher, entrypoint);

			if (mainClass == null) {
				throw new RuntimeException("Could not load main class " + entrypoint + "!");
			}

			// Main -> Game entrypoint search
			//
			// -- CLIENT --
			// pre-1.6 (seems to hold to 0.0.11!): find the only non-static non-java-packaged Object field
			// 1.6.1+: [client].start() [INVOKEVIRTUAL]
			// 19w04a: [client].<init> [INVOKESPECIAL] -> Thread.start()
			// -- SERVER --
			// (1.5-1.7?)-: Just find it instantiating itself.
			// (1.6-1.8?)+: an <init> starting with java.io.File can be assumed to be definite

			if (type == EnvType.CLIENT) {
				// pre-1.6 route
				List<FieldNode> newGameFields = findFields(mainClass,
					(f) -> !isStatic(f.access) && f.desc.startsWith("L") && !f.desc.startsWith("Ljava/")
				);

				if (newGameFields.size() == 1) {
					gameEntrypoint = Type.getType(newGameFields.get(0).desc).getClassName();
				}
			}

			if (gameEntrypoint == null) {
				// main method searches
				MethodNode mainMethod = findMethod(mainClass, (method) -> method.name.equals("main") && method.desc.equals("([Ljava/lang/String;)V") && isPublicStatic(method.access));
				if (mainMethod == null) {
					throw new RuntimeException("Could not find main method in " + entrypoint + "!");
				}

				if (type == EnvType.SERVER) {
					// pre-1.6 method search route
					MethodInsnNode newGameInsn = (MethodInsnNode) findInsn(mainMethod,
						(insn) -> insn.getOpcode() == Opcodes.INVOKESPECIAL && ((MethodInsnNode) insn).name.equals("<init>") && ((MethodInsnNode) insn).owner.equals(mainClass.name),
						false
					);

					if (newGameInsn != null) {
						gameEntrypoint = newGameInsn.owner.replace('/', '.');
						serverHasFile = newGameInsn.desc.startsWith("(Ljava/io/File;");
					}
				}

				if (gameEntrypoint == null) {
					// modern method search routes
					MethodInsnNode newGameInsn = (MethodInsnNode) findInsn(mainMethod,
						type == EnvType.CLIENT
						? (insn) -> (insn.getOpcode() == Opcodes.INVOKESPECIAL || insn.getOpcode() == Opcodes.INVOKEVIRTUAL) && !((MethodInsnNode) insn).owner.startsWith("java/")
						: (insn) -> insn.getOpcode() == Opcodes.INVOKESPECIAL && ((MethodInsnNode) insn).name.equals("<init>") && ((MethodInsnNode) insn).desc.startsWith("(Ljava/io/File;"),
						true
					);

					if (newGameInsn != null) {
						gameEntrypoint = newGameInsn.owner.replace('/', '.');
					}
				}
			}

			if (gameEntrypoint == null) {
				throw new RuntimeException("Could not find game constructor in " + entrypoint + "!");
			}

			logger.debug("Found game constructor: " + entrypoint + " -> " + gameEntrypoint);
			ClassNode gameClass = gameEntrypoint.equals(entrypoint) ? mainClass : loadClass(launcher, gameEntrypoint);
			if (gameClass == null) {
				throw new RuntimeException("Could not load game class " + gameEntrypoint + "!");
			}

			boolean patched = false;
			for (MethodNode gameMethod : gameClass.methods) {
				if (gameMethod.name.equals("<init>")) {
					logger.debug("Patching game constructor " + gameMethod.desc);

					ListIterator<AbstractInsnNode> it = gameMethod.instructions.iterator();
					if (type == EnvType.SERVER) {
						// Server-side: first argument (or null!) is runDirectory, run at end of init
						moveBefore(it, Opcodes.RETURN);
						// runDirectory
						if (serverHasFile) {
							it.add(new VarInsnNode(Opcodes.ALOAD, 1));
						} else {
							it.add(new InsnNode(Opcodes.ACONST_NULL));
						}
						finishEntrypoint(type, it);
						patched = true;
					} else if (type == EnvType.CLIENT && isApplet) {
						// Applet-side: field is private static File, run at end
						// At the beginning, set file field (hook)
						FieldNode runDirectory = findField(gameClass, (f) -> isStatic(f.access) && f.desc.equals("Ljava/io/File;"));
						if (runDirectory == null) {
							// TODO: Handle pre-indev versions.
							//
							// Classic has no agreed-upon run directory.
							// - level.dat is always stored in CWD. We can assume CWD is set, launchers generally adhere to that.
							// - options.txt in newer Classic versions is stored in user.home/.minecraft/. This is not currently handled,
							// but as these versions are relatively low on options this is not a huge concern.
							logger.warn("Could not find applet run directory! (If you're running pre-late-indev versions, this is fine.)");

							moveBefore(it, Opcodes.RETURN);
/*							it.add(new TypeInsnNode(Opcodes.NEW, "java/io/File"));
							it.add(new InsnNode(Opcodes.DUP));
							it.add(new LdcInsnNode("."));
							it.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/io/File", "<init>", "(Ljava/lang/String;)V", false)); */
							it.add(new InsnNode(Opcodes.ACONST_NULL));
							it.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/fabricmc/loader/entrypoint/AppletMain", "hookGameDir", "(Ljava/io/File;)Ljava/io/File;", false));
							finishEntrypoint(type, it);
						} else {
							// Indev and above.
							moveAfter(it, Opcodes.INVOKESPECIAL); /* Object.init */
							it.add(new FieldInsnNode(Opcodes.GETSTATIC, gameClass.name, runDirectory.name, runDirectory.desc));
							it.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/fabricmc/loader/entrypoint/AppletMain", "hookGameDir", "(Ljava/io/File;)Ljava/io/File;", false));
							it.add(new FieldInsnNode(Opcodes.PUTSTATIC, gameClass.name, runDirectory.name, runDirectory.desc));

							moveBefore(it, Opcodes.RETURN);
							it.add(new FieldInsnNode(Opcodes.GETSTATIC, gameClass.name, runDirectory.name, runDirectory.desc));
							finishEntrypoint(type, it);
						}
						patched = true;
					} else {
						// Client-side: identify runDirectory field + location, run immediately after
						while (it.hasNext()) {
							AbstractInsnNode insn = it.next();
							if (insn.getOpcode() == Opcodes.PUTFIELD
								&& ((FieldInsnNode) insn).desc.equals("Ljava/io/File;")) {
								logger.debug("Run directory field is thought to be " + ((FieldInsnNode) insn).owner + "/" + ((FieldInsnNode) insn).name);

								it.add(new VarInsnNode(Opcodes.ALOAD, 0));
								it.add(new FieldInsnNode(Opcodes.GETFIELD, ((FieldInsnNode) insn).owner, ((FieldInsnNode) insn).name, ((FieldInsnNode) insn).desc));
								finishEntrypoint(type, it);

								patched = true;
								break;
							}
						}
					}
				}
			}

			if (!patched) {
				throw new RuntimeException("Game constructor patch not applied!");
			}

			if (gameClass != mainClass) {
				addPatchedClass(gameClass);

				if (applyBrandingPatch(mainClass)) {
					addPatchedClass(mainClass);
				}
			} else {
				applyBrandingPatch(mainClass);
				addPatchedClass(mainClass);
			}

			if (isApplet) {
				appletMainClass = entrypoint;
			}

			for (String brandClassName : ImmutableList.of(
				"net.minecraft.client.ClientBrandRetriever",
				"net.minecraft.server.MinecraftServer"
			)) {
				if (!patchedClasses.containsKey(brandClassName)) {
					ClassNode brandClass = loadClass(launcher, brandClassName);
					if (brandClass != null) {
						if (applyBrandingPatch(brandClass)) {
							addPatchedClass(brandClass);
						}
					}
				}
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private boolean applyBrandingPatch(ClassNode classNode) {
		boolean applied = false;

		for (MethodNode node : classNode.methods) {
			if (node.name.equals("getClientModName") || node.name.equals("getServerModName") && node.desc.endsWith(")Ljava/lang/String;")) {
				logger.debug("Applying brand name hook to " + classNode.name + " " + node.name);

				ListIterator<AbstractInsnNode> it = node.instructions.iterator();
				while (it.hasNext()) {
					if (it.next().getOpcode() == Opcodes.ARETURN) {
						it.previous();
						it.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/fabricmc/loader/entrypoint/EntrypointBranding", "brand", "(Ljava/lang/String;)Ljava/lang/String;", false));
						it.next();
					}
				}

				applied = true;
			}
		}

		return applied;
	}

	private boolean isStatic(int access) {
		return ((access & Opcodes.ACC_STATIC) != 0);
	}

	private boolean isPublicStatic(int access) {
		return ((access & 0x0F) == (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC));
	}

	private boolean isPublicInstance(int access) {
		//noinspection PointlessBitwiseExpression
		return ((access & 0x0F) == (Opcodes.ACC_PUBLIC | 0 /* non-static */));
	}

	/**
	 * This must run first, contractually!
	 * @param className The class name,
	 * @return The transformed class data.
	 */
	public byte[] transform(String className) {
		return patchedClasses.get(className);
	}
}
