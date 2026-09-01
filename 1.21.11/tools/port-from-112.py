#!/usr/bin/env python3
"""Copies a file of the Vulkan half over from the 1.12.2 mod and adapts it.

The Vulkan half is meant to travel unchanged — it knows about Vulkan, not about
Minecraft — and to this version it travels almost intact. **A hand edit made in
a copied file is undone, silently, the next time this runs**, so every
difference belongs here.

What does *not* need adapting on 1.21.11, and did on 1.16.5:

* **LWJGL.** The game brings 3.3.3, one minor version behind the 1.12.2 mod's
  3.3.6. Every struct allocation, `MemoryStack.TLS`, Vulkan 1.2 and the ray
  tracing extensions are all present and spelled the same. The whole
  `malloc(stack)` → `mallocStack(stack)` rewrite the 1.16.5 port needs is gone,
  and so is the stripping of ray tracing.
* **The vertex.** A chunk vertex is 32 bytes here as it is there, laid out the
  same way, so `SOURCE_STRIDE` is the only constant that moves and it moves to
  the same number.

What does need adapting, and is the whole of this file's work: **OpenGL**.
Minecraft asks GLFW for a 3.3 **core profile, forward compatible** context. The
renderer's OpenGL side is written against a compatibility one — immediate mode
quads, the attribute stack, the alpha test, GLSL 120 — and none of that exists
in a core context. It does not warn; it raises an error nobody reads and draws
nothing. The replacements live in `GlCompat`, which is written by hand, and the
rules below only redirect the call sites to it.

Usage: tools/port-from-112.py VkFile.java [more...]
"""

import io
import os
import re
import sys
from collections import OrderedDict

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCE = os.path.join(os.path.dirname(HERE),
                      "src", "main", "java", "net", "vulkanmodnext", "vkimpl")
TARGET = os.path.join(HERE, "src", "main", "java", "net", "vulkanmodnext", "vkimpl")

# A fullscreen quad in immediate mode, in the two shapes the renderer uses: bare
# positions, and positions with texture coordinates. Written as patterns because
# the indentation differs between call sites and the bodies do not.
QUAD = re.compile(
    r"([ \t]*)org\.lwjgl\.opengl\.GL11\.glBegin\(org\.lwjgl\.opengl\.GL11\.GL_QUADS\);\s*"
    r"(?:[ \t]*org\.lwjgl\.opengl\.GL11\.glVertex2f\([^;]*\);\s*){4}"
    r"[ \t]*org\.lwjgl\.opengl\.GL11\.glEnd\(\);")
TEXTURED_QUAD = re.compile(
    r"([ \t]*)org\.lwjgl\.opengl\.GL11\.glBegin\(org\.lwjgl\.opengl\.GL11\.GL_QUADS\);\s*"
    r"(?:[ \t]*org\.lwjgl\.opengl\.GL11\.glTexCoord2f\([^;]*\);\s*"
    r"[ \t]*org\.lwjgl\.opengl\.GL11\.glVertex2f\([^;]*\);\s*){4}"
    r"[ \t]*org\.lwjgl\.opengl\.GL11\.glEnd\(\);")
# The attribute stack. The bits are dropped on purpose: GlCompat saves a fixed
# list, and which bits were named here says what the pass changes rather than
# what has to be restored.
PUSH_ATTRIB = re.compile(
    r"org\.lwjgl\.opengl\.GL11\.glPushAttrib\([^;]*\);")
POP_ATTRIB = re.compile(r"org\.lwjgl\.opengl\.GL11\.glPopAttrib\(\);")
ALPHA_TEST = re.compile(
    r"GL11C\.glDisable\(org\.lwjgl\.opengl\.GL11\.GL_ALPHA_TEST\);")

# The one vertex shader, repeated three times, and the only one that reads a
# built-in vertex attribute. Location zero is stated in the shader rather than
# bound afterwards so that it cannot drift from the buffer GlCompat sets up.
LEGACY_VERTEX = re.compile(
    r'"#version 120\\n"\s*\n(\s*)\+ "void main\(\) \{ gl_Position = '
    r'vec4\(gl_Vertex\.xy, 0\.0, 1\.0\); \}\\n"')

GLOBAL = [
    # Only the setup half of VulkanContextImpl came across, under a name that
    # says so. Everything that took a reference to the old one wants this.
    ("VulkanContextImpl", "VkContext"),

    # A qualified stack push. Caught before the unqualified one below, or it
    # becomes MemoryStack.push() — a real method on a real class, which fails
    # only at the point of use.
    ("MemoryStack.stackPush()", "net.vulkanmodnext.VkStack.push()"),

    # --- the core profile ---
    (LEGACY_VERTEX,
     r'"#version 330 core\\n"\n\1+ "layout(location = 0) in vec2 aPos;\\n"\n'
     r'\1+ "void main() { gl_Position = vec4(aPos, 0.0, 1.0); }\\n"'),
    # Whatever is left starting a shader is a fragment shader, and a core one
    # has to declare its own output.
    ('"#version 120\\n"', '"#version 330 core\\nout vec4 fragColor;\\n"'),
    ("gl_FragColor", "fragColor"),
    ("texture2D(", "texture("),

    # The textured one is the startup probe, and it is the only place that
    # sampled without a shader. Its texture is already bound on unit 0 by the
    # two lines above it; the line below removes the fixed-function enable that
    # went with them, which a core context rejects outright.
    (re.compile(r"[ \t]*GL11C\.glEnable\(GL11C\.GL_TEXTURE_2D\);\n"), ""),
    (TEXTURED_QUAD, r"\1GlCompat.sampleTextureFullscreen();"),
    (QUAD, r"\1GlCompat.drawFullscreenQuad();"),
    (PUSH_ATTRIB, "GlCompat.pushState();"),
    (POP_ATTRIB, "GlCompat.popState();"),
    (ALPHA_TEST, "GlCompat.disableAlphaTest();"),
]

PER_FILE = OrderedDict()

PER_FILE["VertexLayout.java"] = [
    # A vanilla chunk vertex is 28 bytes on 1.12.2 and 32 here — the same 32 as
    # on 1.16.5, and laid out identically: position, colour, texture and light
    # first, a three-byte normal and a byte of padding appended. Every field
    # offset in the packing is already right; only the step from one vertex to
    # the next changes.
    #
    # It is one constant, and it is the one that hurts if it is wrong: a stride
    # off by four bytes raises no error at all, it draws a world of spikes.
    ("public static final int SOURCE_STRIDE = 28;",
     "public static final int SOURCE_STRIDE = 32;"),
]

PER_FILE["VkChunkMirror.java"] = [
    # The staging ring is built lazily on 1.12.2, by the render thread, the
    # first time it mirrors a section itself. VkContext was assembled for 1.16.5
    # where that never happens — every section arrives on a builder thread — so
    # it primes the ring up front instead, and calls this. Here the render
    # thread does upload the sections, so the lazy path would work; the method
    # is kept because VkContext is shared with that port and because worker
    # staging is where this one is going.
    ("    private void ensureStagingRing(int needed) {",
     """    /** Creates the staging ring before any builder thread asks for a range. */
    synchronized void prime() {
        ensureStagingRing((int) STAGING_RING_MIN);
    }

    private void ensureStagingRing(int needed) {"""),
]

PER_FILE["VkTerrainRenderer.java"] = [
    # A way in to the image Vulkan draws into, for the probe that reads it
    # back. It is the one question that splits a black screen in half: terrain
    # in this image and not on the screen is the composite's fault; nothing in
    # either is the Vulkan draw's. Kept in the script rather than the copy so
    # that re-porting does not take the instrument away.
    ("    private void composite() {",
     """    /** The OpenGL name of the image Vulkan draws the terrain into. */
    public synchronized int sharedColourTexture() {
        return glColorTexture;
    }

    private void composite() {"""),

    # Smart animations are a game-side feature and are not ported yet. A report
    # that prints a section for something that does not exist is worse than one
    # that omits it.
    ("""        String animations = net.vulkanmodnext.client.AnimatedSprites.stats();
        if (animations != null && net.vulkanmodnext.client.VulkanConfig.isSmartAnimations()) {
            sb.append(animations).append('\\n');
        }""",
     """        // Smart animations are a game-side feature and are not ported yet."""),
]


def convert(text, rules, named):
    counts = {}

    for before, after in rules:
        # A rule is either a literal or a compiled pattern. Patterns are for the
        # cases where a literal would be too greedy, or where indentation
        # differs between otherwise identical call sites.
        if hasattr(before, "sub"):
            text, hits = before.subn(after, text)
        elif before in text:
            hits = text.count(before)
            text = text.replace(before, after)
        else:
            hits = 0
        if hits:
            counts["rewritten"] = counts.get("rewritten", 0) + hits
        elif (before, after) in named:
            # A rule written for this very file found nothing: the 1.12.2 source
            # moved under this script, and the copy is now missing a change
            # somebody decided it needed. Silence here is a compile error four
            # hundred lines away, or worse, none at all.
            counts["RULES THAT MATCHED NOTHING"] = counts.get(
                    "RULES THAT MATCHED NOTHING", 0) + 1

    pushes = text.count("stackPush()")
    if pushes:
        counts["stack frames"] = pushes
        text = text.replace("import static org.lwjgl.system.MemoryStack.stackPush;",
                            "import static net.vulkanmodnext.VkStack.push;")
        text = text.replace("stackPush()", "push()")
    return text, counts


def main(names):
    if not names:
        print(__doc__)
        return 2
    os.makedirs(TARGET, exist_ok=True)
    failed = False
    for name in names:
        source = os.path.join(SOURCE, name)
        if not os.path.exists(source):
            print("not in the 1.12.2 mod: " + name, file=sys.stderr)
            return 1
        text = io.open(source, encoding="utf-8").read()
        named = PER_FILE.get(name, [])
        converted, counts = convert(text, GLOBAL + named, named)
        io.open(os.path.join(TARGET, name), "w", encoding="utf-8").write(converted)
        summary = ", ".join("%d %s" % (v, k) for k, v in sorted(counts.items())) or "nothing"
        print("%-28s %d lines, adapted: %s" % (name, text.count("\n") + 1, summary))
        failed = failed or "RULES THAT MATCHED NOTHING" in counts
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
