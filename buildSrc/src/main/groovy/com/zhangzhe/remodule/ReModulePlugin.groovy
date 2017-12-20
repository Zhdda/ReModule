package com.zhangzhe.remodule

import groovy.xml.DOMBuilder
import groovy.xml.XmlUtil
import org.gradle.api.Plugin
import org.gradle.api.Project

class ReModulePlugin implements Plugin<Project> {

    //默认是app，直接运行assembleRelease的时候，等同于运行app:assembleRelease
    String compilemodule = "app"

    void apply(Project project) {
        //  project.extensions.create('combuild', ComExtension)

        //获取当前task名
        String taskNames = project.gradle.startParameter.taskNames.toString()
        System.out.println("taskNames is " + taskNames);
        //获取当前module的名
        String module = project.path.replace(":", "")
        System.out.println("current module is " + module);

        CurrTask currTask = getTaskInfo(project.gradle.startParameter.taskNames)

        if (currTask.isAssemble) {
            fetchMainmodulename(project, currTask);
            System.out.println("compile module  is " + compilemodule);
        }

        //对于isRunAlone==true的情况需要根据实际情况修改其值，
        // 但如果是false，则不用修改，该module作为一个lib，运行module:assembleRelease则发布aar到中央仓库
        boolean isRunAlone = true
        String mainmodulename = project.rootProject.property("mainmodulename")

        //对于要编译的组件和主项目，isRunAlone修改为true，其他组件都强制修改为false
        //这就意味着组件不能引用主项目，这在层级结构里面也是这么规定的
        if (currTask.isUpload) {
            isRunAlone = false
        } else {
            isRunAlone = true
            System.out.println("isRunAlone  is " + isRunAlone)
        }

        project.ext.isRunAlone = isRunAlone

        System.out.println("isRunAlone  is " + isRunAlone)

        //根据配置添加各种组件依赖，并且自动化生成组件加载代码
        if (isRunAlone) {
            project.apply plugin: 'com.android.application'
            if (!module.equals(mainmodulename)) {
                project.android.sourceSets {
                    main {
                        manifest.srcFile 'src/main/AndroidManifest.xml'
                        java.srcDirs = ['src/main/java', 'src/main/debug/java']
                        res.srcDirs = ['src/main/res', 'src/main/debug/res']
                    }
                }
            }

            System.out.println("apply plugin is " + 'com.android.application');
            if (currTask.isAssemble && module.equals(compilemodule)) {
//                compileComponents(currTask, project)
//                project.android.registerTransform(new ComCodeTransform(project))
            }
        } else {
            project.apply plugin: 'com.android.library'
            System.out.println("apply plugin is " + 'com.android.library')
            if (!module.equals(mainmodulename)) {
                project.android.sourceSets {
                    main {
                        manifest.srcFile 'build/manifest/AndroidManifest.xml'
                        java.srcDirs = ['src/main/java']
                        res.srcDirs = ['src/main/res']
                    }
                }
                project.android
            }

            project.afterEvaluate {
                File infile = project.file("src/main/AndroidManifest.xml")
                File outfile = project.file("build/manifest")
                project.copy {
                    from infile
                    into outfile
                }
                updateManifestXml("build/manifest/AndroidManifest.xml")

                System.out.println("$module-release.aar copy success ")

            }


        }

    }

    /**
     * 根据当前的task，获取要运行的组件，规则如下：
     * assembleRelease ---app
     * app:assembleRelease :app:assembleRelease ---app
     * sharecomponent:assembleRelease :sharecomponent:assembleRelease ---sharecomponent
     * @param assembleTask
     */
    private void fetchMainmodulename(Project project, CurrTask assembleTask) {
        if (!project.rootProject.hasProperty("mainmodulename")) {
            throw new RuntimeException("you should set compilemodule in rootproject's gradle.properties")
        }
        if (assembleTask.modules.size() > 0 && assembleTask.modules.get(0) != null
                && assembleTask.modules.get(0).trim().length() > 0
                && !assembleTask.modules.get(0).equals("all")) {
            compilemodule = assembleTask.modules.get(0);
        } else {
            compilemodule = project.rootProject.property("mainmodulename")
        }
        if (compilemodule == null || compilemodule.trim().length() <= 0) {
            compilemodule = "app"
        }
    }

    private CurrTask getTaskInfo(List<String> taskNames) {
        CurrTask assembleTask = new CurrTask();
        for (String task : taskNames) {
            if (task.toUpperCase().contains("UPLOAD")||
                    task.toUpperCase().contains("ARCHIVUES")||
                    task.toUpperCase().contains("UA")||
                    task.toUpperCase().contains("UPA")
            ) {
                if (task.toUpperCase().contains("DEBUG")) {
                    assembleTask.isDebug = true
                }
                assembleTask.isUpload = true
                String[] strs = task.split(":")
                assembleTask.modules.add(strs.length > 1 ? strs[strs.length - 2] : "all");
                break
            }

        }


        return assembleTask
    }

    /**
     * 当前task 属性封装
     */
    private class CurrTask {
        boolean isAssemble = false
        boolean isDebug = false
        boolean isUpload = false;
        List<String> modules = new ArrayList<>()
    }

    /**
     * 去掉<application>标签中所有属性
     * 去掉带启动intentfilter的activity
     * @param path
     */
    void updateManifestXml(def path) {
        File file = new File(path)

        def doc = DOMBuilder.parse(new StringReader(file.text))
        def root = doc.documentElement
        use(groovy.xml.dom.DOMCategory) {
            //移除掉默认启动intent-filter 以及 基地标志的intent-filter
            removeIntentFilterByAction_Categroy(root, "android.intent.action.MAIN", "android.intent.category.LAUNCHER")
            //清除application 节点的各种属性避免作为。arr merge manifest时候 属性覆盖主工程application
            clearApplication(root)


        }

        def result = XmlUtil.serialize(root)
        file.write(result, "UTF-8")
        // println result
    }

    void clearApplication(def root) {
        root.getElementsByTagName("application").each { org.w3c.dom.Element node ->
            node.removeAttribute("android:icon")
            node.removeAttribute("android:label")
            node.removeAttribute("android:roundIcon")
            node.removeAttribute("android:supportsRtl")
            node.removeAttribute("android:theme")
            node.removeAttribute("android:name")
            node.removeAttribute("android:allowBackup")
        }
    }

    void addMainIntentFilter(def root, String name) {
        root.getElementsByTagName("activity").each { node ->
            if (node.attributes.getNamedItem("android:name").nodeValue == name) {
                org.w3c.dom.Element element = node.appendNode("intent-filter")
                element.appendNode("action", ["android:name": "android.intent.action.MAIN"])
                element.appendNode("category", ["android:name": "android.intent.category.LAUNCHER"])
            }
        }
    }


    void removeActivityByName(def root, String name) {
        root.getElementsByTagName("activity").each { node ->
            if (node.attributes.getNamedItem("android:name").nodeValue == name) {
                node.parentNode.removeChild(node)
            }
        }
    }


    void removeIntentFilterByAction_Categroy(def root, String actionValue, String categroyValue) {
        root.getElementsByTagName("intent-filter").each { node ->
            boolean hasMain
            boolean hasLuancher
            node.childNodes.each { childNode ->
                if (childNode.nodeName == "action" && actionValue == childNode.attributes.getNamedItem("android:name").nodeValue) {
                    hasMain = true
                }
                if (childNode.nodeName == "category" && categroyValue == childNode.attributes.getNamedItem("android:name").nodeValue) {
                    hasLuancher = true
                }
            }
            if (hasMain && hasLuancher) {
                node.parentNode.removeChild(node)
            }
        }
    }
}