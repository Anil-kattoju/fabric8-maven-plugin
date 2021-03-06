/**
 * Copyright 2016 Red Hat, Inc.
 *
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */
package io.fabric8.maven.core.util;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.fabric8.maven.docker.util.Logger;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.Modifier;
import javassist.NotFoundException;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.maven.project.MavenProject;

/**
 * @author roland
 * @since 24/07/16
 */
public class ClassUtil {

    public static Set<String> getResources(String resource) throws IOException {
        return getResources(resource, null);
    }

    public static Set<String> getResources(String resource, List<ClassLoader> additionalClassLoaders) throws IOException {

        ClassLoader[] classLoaders = mergeClassLoaders(additionalClassLoaders);

        Set<String> ret = new HashSet<>();
        for (ClassLoader cl : classLoaders) {
            Enumeration<URL> urlEnum = cl.getResources(resource);
            ret.addAll(extractUrlAsStringsFromEnumeration(urlEnum));
        }
        return ret;
    }

    private static ClassLoader[] mergeClassLoaders(List<ClassLoader> additionalClassLoaders) {
        ClassLoader[] classLoaders;

        if (additionalClassLoaders != null && !additionalClassLoaders.isEmpty()) {
            classLoaders = ArrayUtils.addAll(getClassLoaders(), additionalClassLoaders.toArray(new ClassLoader[additionalClassLoaders.size()]));
        }
        else {
            classLoaders = getClassLoaders();
        }
        return classLoaders;
    }


    private static ClassLoader[] getClassLoaders() {
        return new ClassLoader[] {
            Thread.currentThread().getContextClassLoader(),
            PluginServiceFactory.class.getClassLoader()
        };
    }

    private static Set<String> extractUrlAsStringsFromEnumeration(Enumeration<URL> urlEnum) {
        Set<String> ret = new HashSet<String>();
        while (urlEnum.hasMoreElements()) {
            ret.add(urlEnum.nextElement().toExternalForm());
        }
        return ret;
    }


    public static <T> Class<T> classForName(String className, List<ClassLoader> additionalClassLoaders) {
        ClassLoader[] classLoaders = mergeClassLoaders(additionalClassLoaders);
        Set<ClassLoader> tried = new HashSet<>();
        for (ClassLoader loader : classLoaders) {
            // Go up the classloader stack to eventually find the server class. Sometimes the WebAppClassLoader
            // hide the server classes loaded by the parent class loader.
            while (loader != null) {
                try {
                    if (!tried.contains(loader)) {
                        return (Class<T>) Class.forName(className, true, loader);
                    }
                } catch (ClassNotFoundException ignored) {}
                tried.add(loader);
                loader = loader.getParent();
            }
        }
        return null;
    }


    /**
     * Find all classes below a certain directory which contain
     * main() classes
     *
     * @param rootDir the directory to start from
     * @return List of classes with "public void static main(String[] args)" methods. Can be empty, but not null.
     * @exception IOException if something goes wrong
     */
    public static List<String> findMainClasses(File rootDir) throws IOException {
        List<String> ret = new ArrayList<>();
        if (!rootDir.exists()) {
            return ret;
        }
        if (!rootDir.isDirectory()) {
            throw new IllegalArgumentException(String.format("Path %s is not a directory",rootDir.getPath()));
        }
        findClasses(ret, rootDir, rootDir.getAbsolutePath() + "/");
        return ret;
    }

    // ========================================================================

    private static final FileFilter DIR_FILTER = new FileFilter() {
        @Override
        public boolean accept(File pathname) {
            return pathname.isDirectory() && !pathname.getName().startsWith(".");
        }
    };

    private static final FileFilter CLASS_FILE_FILTER = new FileFilter() {
		@Override
        public boolean accept(File file) {
            return (file.isFile() && file.getName().endsWith(".class"));
		}
	};


    private static void findClasses(List<String> classes, File dir, String prefix) throws IOException {
        for (File subDir : dir.listFiles(DIR_FILTER)) {
            findClasses(classes, subDir, prefix);
        }

        for (File classFile : dir.listFiles(CLASS_FILE_FILTER)) {
            try (InputStream is = new FileInputStream(classFile)) {
                if (hasMainMethod(is)) {
                    classes.add(convertToClass(classFile.getAbsolutePath(), prefix));
                }
            }
        }
    }

    private static boolean hasMainMethod(InputStream is) throws IOException {
        try {
            ClassPool pool = ClassPool.getDefault();
            CtClass ctClass = pool.makeClass(is);
            CtClass stringClass = pool.get("java.lang.String[]");
            CtMethod mainMethod = ctClass.getDeclaredMethod("main", new CtClass[] { stringClass });
            return mainMethod.getReturnType() == CtClass.voidType &&
                   Modifier.isStatic(mainMethod.getModifiers()) &&
                   Modifier.isPublic(mainMethod.getModifiers());
        } catch (NotFoundException e) {
            return false;
        }
    }

    private static String convertToClass(String name, String prefix) {
        String ret = name.replaceAll("[/\\\\]", ".");
        ret = ret.substring(0, name.length() - ".class".length());
        return ret.substring(prefix.length());
    }


    public static URLClassLoader createProjectClassLoader(List<String> elements, Logger log) {

        try {

            List<URL> compileJars = new ArrayList<>();

            for (String element : elements) {
                compileJars.add(new File(element).toURI().toURL());
            }

            return new URLClassLoader(compileJars.toArray(new URL[compileJars.size()]),
                    PluginServiceFactory.class.getClassLoader());

        } catch (Exception e) {
            log.warn("Instructed to use project classpath, but cannot. Continuing build if we can: ", e);
        }

        // return an empty CL .. don't want to have to deal with NULL later
        // if somehow we incorrectly call this method
        return new URLClassLoader(new URL[]{});
    }
}
