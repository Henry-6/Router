package com.chenenyu.router

import com.android.build.api.transform.*
import com.android.build.gradle.internal.pipeline.TransformManager
import com.android.ide.common.internal.WaitableExecutor
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.FileUtils
import org.gradle.api.Project

import java.util.concurrent.Callable

/**
 * Created by chenenyu on 2018/7/24.
 */
class RouterTransform extends Transform {
    static File registerTargetFile = null

    Project project

    WaitableExecutor waitableExecutor

    RouterTransform(Project project) {
        this.project = project
        this.waitableExecutor = WaitableExecutor.useGlobalSharedThreadPool()
    }

    @Override
    String getName() {
        return "router"
    }

    @Override
    Set<QualifiedContent.ContentType> getInputTypes() {
        return TransformManager.CONTENT_CLASS
    }

    @Override
    Set<? super QualifiedContent.Scope> getScopes() {
        return TransformManager.SCOPE_FULL_PROJECT
    }

    @Override
    boolean isIncremental() {
        return false
    }

    @Override
    void transform(Context context,
                   Collection<TransformInput> inputs,
                   Collection<TransformInput> referencedInputs,
                   TransformOutputProvider outputProvider,
                   boolean isIncremental)
            throws IOException, TransformException, InterruptedException {
    }

    @Override
    void transform(TransformInvocation transformInvocation)
            throws TransformException, InterruptedException, IOException {
        long begin = System.currentTimeMillis()
        project.logger.info("- router transform begin:")

        Scanner.records = Scanner.getRecords(project.name)

        transformInvocation.inputs.each { TransformInput input ->
            if (!input.jarInputs.empty) {
                project.logger.info("-- jarInputs:")
                input.jarInputs.each { JarInput jarInput ->
                    execute {
                        transformJar(transformInvocation, jarInput)
                    }
                }
            }

            if (!input.directoryInputs.empty) {
                project.logger.info("-- directoryInputs:")
                input.directoryInputs.each { DirectoryInput directoryInput ->
                    execute {
                        transformDir(transformInvocation, directoryInput)
                    }
                }
            }
        }

        waitableExecutor.waitForAllTasks()

        // 找到了AptHub.class 向其注入代码
        if (registerTargetFile) {
            project.logger.info("begin to register code to ${registerTargetFile.absolutePath}")
            Knife.handle()
        } else {
            project.logger.warn("router: register target file not found.")
        }
        project.logger.info("- router transform finish.")
        project.logger.info("cost time: ${(System.currentTimeMillis() - begin) / 1000.0f}s")
    }

    void transformJar(TransformInvocation transformInvocation, JarInput jarInput) {
        // com.android.support:appcompat-v7:27.1.1 (/path/to/xxx.jar)
        project.logger.info("--- ${jarInput.name} (${jarInput.file.absolutePath})")
        String destName = jarInput.name
        if (destName.endsWith(".jar")) { // local jar
            // rename to avoid the same name, such as classes.jar
            String hexName = DigestUtils.md5Hex(jarInput.file.absolutePath)
            destName = "${destName.substring(0, destName.length() - 4)}_${hexName}"
        }
        File destFile = transformInvocation.outputProvider.getContentLocation(
                destName, jarInput.contentTypes, jarInput.scopes, Format.JAR)
        if (Scanner.shouldScanJar(jarInput)) {
            Scanner.scanJar(jarInput.file, destFile)
        }

        FileUtils.copyFile(jarInput.file, destFile)
    }

    void transformDir(TransformInvocation transformInvocation, DirectoryInput directoryInput) {
        project.logger.info("-- directory: ${directoryInput.name} (${directoryInput.file.absolutePath})")
        File dest = transformInvocation.outputProvider.getContentLocation(
                directoryInput.name, directoryInput.contentTypes, directoryInput.scopes, Format.DIRECTORY)
        project.logger.info("-- dest dir: ${dest.absolutePath}")
        directoryInput.file.eachFileRecurse { File file ->
            if (file.isFile() && Scanner.shouldScanClass(file)) {
                project.logger.info("--- ${file.absolutePath}")
                Scanner.scanClass(file)
            }
        }

        FileUtils.copyDirectory(directoryInput.file, dest)
    }

    void execute(Closure closure) {
        waitableExecutor.execute(new Callable<Void>() {
            @Override
            Void call() throws Exception {
                closure()
                return null
            }
        })
    }
}
