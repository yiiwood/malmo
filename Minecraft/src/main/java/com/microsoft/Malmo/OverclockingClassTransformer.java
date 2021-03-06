// --------------------------------------------------------------------------------------------------
//  Copyright (c) 2016 Microsoft Corporation
//  
//  Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
//  associated documentation files (the "Software"), to deal in the Software without restriction,
//  including without limitation the rights to use, copy, modify, merge, publish, distribute,
//  sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
//  furnished to do so, subject to the following conditions:
//  
//  The above copyright notice and this permission notice shall be included in all copies or
//  substantial portions of the Software.
//  
//  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
//  NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
//  NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
//  DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
//  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
// --------------------------------------------------------------------------------------------------

package com.microsoft.Malmo;

import net.minecraft.launchwrapper.IClassTransformer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

public class OverclockingClassTransformer implements IClassTransformer
{
    enum transformType { SERVER, RENDERER }
    
    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass)
    {
        boolean isObfuscated = !name.equals(transformedName);
        if (transformedName.equals("net.minecraft.server.MinecraftServer"))
            return transform(basicClass, isObfuscated, transformType.SERVER);
        else if (transformedName.equals("net.minecraft.client.Minecraft"))
            return transform(basicClass, isObfuscated, transformType.RENDERER);
        else
            return basicClass;
    }

    private static byte[] transform(byte[] serverClass, boolean isObfuscated, transformType type)
    {
        System.out.println("MALMO: Attempting to transform MinecraftServer");
        try
        {
            ClassNode cnode = new ClassNode();
            ClassReader creader = new ClassReader(serverClass);
            creader.accept(cnode, 0);
            
            switch (type)
            {
            case SERVER:
                overclockServer(cnode, isObfuscated);
                break;
            case RENDERER:
                overclockRenderer(cnode, isObfuscated);
                break;
            }
            
            ClassWriter cwriter = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            cnode.accept(cwriter);
            return cwriter.toByteArray();
        }
        catch (Exception e)
        {
            System.out.println("MALMO FAILED to transform MinecraftServer - overclocking not available!");
        }
        return serverClass;
    }
    
    private static void overclockServer(ClassNode node, boolean isObfuscated)
    {
        // We're attempting to replace this code (from the heart of MinecraftServer.run):
        /*       
            {
                while (i > 50L)
                {
                    i -= 50L;
                    this.tick();
                }
            }
    
            Thread.sleep(Math.max(1L, 50L - i));
        */

        // With this:
        /*       
        {
            while (i > TimeHelper.serverTickLength)
            {
                i -= TimeHelper.serverTickLength;
                this.tick();
            }
        }

        Thread.sleep(Math.max(1L, TimeHelper.serverTickLength - i));
    */
        // This allows us to alter the tick length via TimeHelper.
        
        final String methodName = "run";
        final String methodDescriptor = "()V"; // No params, returns void.

        System.out.println("MALMO: Found MinecraftServer, attempting to transform it");

        for (MethodNode method : node.methods)
        {
            if (method.name.equals(methodName) && method.desc.equals(methodDescriptor))
            {
                System.out.println("MALMO: Found MinecraftServer.run() method, attempting to transform it");
                for (AbstractInsnNode instruction : method.instructions.toArray())
                {
                    if (instruction.getOpcode() == Opcodes.LDC)
                    {
                        Object cst = ((LdcInsnNode)instruction).cst;
                        if ((cst instanceof Long) && (Long)cst == 50)
                        {
                            System.out.println("MALMO: Transforming LDC");
                            AbstractInsnNode replacement = new FieldInsnNode(Opcodes.GETSTATIC, "com/microsoft/Malmo/Utils/TimeHelper", "serverTickLength", "J");
                            method.instructions.set(instruction, replacement);
                        }
                    }
                }
            }
        }
    }
    
    private static void overclockRenderer(ClassNode node, boolean isObfuscated)
    {
        // We're attempting to turn this line from Minecraft.runGameLoop:
        //          this.updateDisplay();
        // into this:
        //          TimeHelper.updateDisplay();
        // TimeHelper's method then decides whether or not to pass the call on to Minecraft.updateDisplay().
        
        final String methodName = isObfuscated ? "as" : "runGameLoop";
        final String methodDescriptor = "()V"; // No params, returns void.

        System.out.println("MALMO: Found Minecraft, attempting to transform it");

        for (MethodNode method : node.methods)
        {
            if (method.name.equals(methodName) && method.desc.equals(methodDescriptor))
            {
                System.out.println("MALMO: Found Minecraft.runGameLoop() method, attempting to transform it");
                for (AbstractInsnNode instruction : method.instructions.toArray())
                {
                    if (instruction.getOpcode() == Opcodes.INVOKEVIRTUAL)
                    {
                        MethodInsnNode visitMethodNode = (MethodInsnNode)instruction;
                        if (visitMethodNode.name.equals(isObfuscated ? "h" : "updateDisplay"))
                        {
                            visitMethodNode.owner = "com/microsoft/Malmo/Utils/TimeHelper";
                            if (isObfuscated)
                            {
                                visitMethodNode.name = "updateDisplay";
                            }
                            visitMethodNode.setOpcode(Opcodes.INVOKESTATIC);
                            method.instructions.remove(visitMethodNode.getPrevious());  // ALOAD 0 not needed for static invocation.
                            System.out.println("MALMO: Hooked into call to Minecraft.updateDisplay()");
                        }
                    }
                }
            }
        }
    }
}
