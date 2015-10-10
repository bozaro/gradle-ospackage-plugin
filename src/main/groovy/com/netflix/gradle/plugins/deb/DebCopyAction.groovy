/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.gradle.plugins.deb

import com.netflix.gradle.plugins.deb.control.MultiArch
import com.netflix.gradle.plugins.deb.validation.DebTaskPropertiesValidator
import com.netflix.gradle.plugins.packaging.AbstractPackagingCopyAction
import com.netflix.gradle.plugins.packaging.Dependency
import com.netflix.gradle.plugins.packaging.Directory
import com.netflix.gradle.plugins.packaging.Link
import groovy.transform.Canonical
import org.apache.commons.lang3.StringUtils
import org.apache.commons.lang3.time.DateFormatUtils
import org.gradle.api.GradleException
import org.gradle.api.internal.file.copy.CopyAction
import org.gradle.api.internal.file.copy.FileCopyDetailsInternal
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.vafer.jdeb.Compression
import org.vafer.jdeb.Console
import org.vafer.jdeb.DataProducer
import org.vafer.jdeb.DebMaker
import org.vafer.jdeb.producers.DataProducerLink

/**
 * Forked and modified from org.jamel.pkg4j.gradle.tasks.BuildDebTask
 */
class DebCopyAction extends AbstractPackagingCopyAction<Deb> {
    static final Logger logger = LoggerFactory.getLogger(DebCopyAction.class)

    File debianDir
    List<String> dependencies
    List<String> conflicts
    List<String> recommends
    List<String> suggests
    List<String> enhances
    List<String> preDepends
    List<String> breaks
    List<String> replaces
    List<DataProducer> dataProducers
    List<InstallDir> installDirs
    TemplateHelper templateHelper
    private final DebTaskPropertiesValidator debTaskPropertiesValidator = new DebTaskPropertiesValidator()
    private DebFileVisitorStrategy debFileVisitorStrategy

    DebCopyAction(Deb debTask) {
        super(debTask)
        debTaskPropertiesValidator.validate(debTask)
        dependencies = []
        conflicts = []
        recommends = []
        suggests = []
        enhances = []
        preDepends = []
        breaks = []
        replaces = []
        dataProducers = []
        installDirs = []
        debianDir = new File(task.project.buildDir, "debian")
        templateHelper = new TemplateHelper(debianDir, '/deb')
        debFileVisitorStrategy = new DebFileVisitorStrategy(dataProducers, installDirs)
    }

    @Canonical
    static class InstallDir {
        String name
        String user
        String group
    }

    @Override
    void startVisit(CopyAction action) {
        super.startVisit(action)

        debianDir.deleteDir()
        debianDir.mkdirs()

    }

    @Override
    void visitFile(FileCopyDetailsInternal fileDetails, def specToLookAt) {
        logger.debug "adding file {}", fileDetails.relativePath.pathString

        def inputFile = extractFile(fileDetails)

        String user = lookup(specToLookAt, 'user') ?: task.user
        int uid = (int) lookup(specToLookAt, 'uid') ?: task.uid ?: 0
        String group = lookup(specToLookAt, 'permissionGroup') ?: task.permissionGroup ?: user
        int gid = (int) lookup(specToLookAt, 'gid') ?: task.gid ?: 0

        int fileMode = fileDetails.mode

        debFileVisitorStrategy.addFile(fileDetails, inputFile, user, uid, group, gid, fileMode)
    }

    @Override
    void visitDir(FileCopyDetailsInternal dirDetails, def specToLookAt) {
        def specCreateDirectoryEntry = lookup(specToLookAt, 'createDirectoryEntry')
        boolean createDirectoryEntry = specCreateDirectoryEntry!=null ? specCreateDirectoryEntry : task.createDirectoryEntry
        if (createDirectoryEntry) {

            logger.debug "adding directory {}", dirDetails.relativePath.pathString
            String user = lookup(specToLookAt, 'user') ?: task.user
            int uid = (int) lookup(specToLookAt, 'uid') ?: task.uid ?: 0
            String group = lookup(specToLookAt, 'permissionGroup') ?: task.permissionGroup ?: user
            int gid = (int) lookup(specToLookAt, 'gid') ?: task.gid ?: 0

            int fileMode = dirDetails.mode

            debFileVisitorStrategy.addDirectory(dirDetails, user, uid, group, gid, fileMode)
        }
    }

    @Override
    protected void addLink(Link link) {
        dataProducers << new DataProducerLink(link.path, link.target, true, null, null, null);
    }

    @Override
    protected void addDependency(Dependency dep) {
        dependencies << dep.toDebString()
    }

    @Override
    protected void addConflict(Dependency dep) {
        conflicts << dep.toDebString()
    }

    @Override
    protected void addObsolete(Dependency dep) {
        logger.warn "Obsoletes functionality not implemented for deb files"
    }

    protected void addRecommends(Dependency dep) {
        recommends << dep.toDebString()
    }

    protected void addSuggests(Dependency dep) {
        suggests << dep.toDebString()
    }

    protected void addEnhances(Dependency dep) {
        enhances << dep.toDebString()
    }

    protected void addPreDepends(Dependency dep) {
        preDepends << dep.toDebString()
    }

    protected void addBreaks(Dependency dep) {
        breaks << dep.toDebString()
    }

    protected void addReplaces(Dependency dep) {
        replaces << dep.toDebString()
    }

    @Override
    protected void addDirectory(Directory directory) {
        logger.warn 'Directory functionality not implemented for deb files'
    }

    protected String getMultiArch() {
        def archString = task.getArchString()
        def multiArch = task.getMultiArch()
        if (('all' == archString) && (MultiArch.SAME == multiArch)) {
            throw new IllegalArgumentException('Deb packages with Architecture: all cannot declare Multi-Arch: same')
        }
        def multiArchString = multiArch?.name()?.toLowerCase() ?: ''
        return multiArchString
    }

    protected Map<String,String> getCustomFields() {
        task.getAllCustomFields().collectEntries { String key, String val ->
            // in the deb control file, header XB-Foo becomes Foo in the binary package
            ['XB-' + key.capitalize(), val]
        }
    }

    @Override
    protected void end() {
        for (Dependency recommends : task.allRecommends) {
            logger.debug "adding recommends on {} {}", recommends.packageName, recommends.version
            addRecommends(recommends)
        }

        for (Dependency suggests : task.allSuggests) {
            logger.debug "adding suggests on {} {}", suggests.packageName, suggests.version
            addSuggests(suggests)
        }

        for (Dependency enhances : task.allEnhances) {
            logger.debug "adding enhances on {} {}", enhances.packageName, enhances.version
            addEnhances(enhances)
        }

        for (Dependency preDepends : task.allPreDepends) {
            logger.debug "adding preDepends on {} {}", preDepends.packageName, preDepends.version
            addPreDepends(preDepends)
        }

        for (Dependency breaks : task.getAllBreaks()) {
            logger.debug "adding breaks on {} {}", breaks.packageName, breaks.version
            addBreaks(breaks)
        }

        for (Dependency replaces : task.allReplaces) {
            logger.debug "adding replaces on {} {}", replaces.packageName, replaces.version
            addReplaces(replaces)
        }

        File debFile = task.getArchivePath()

        def context = toContext()
        List<File> debianFiles = new ArrayList<File>();

        debianFiles << templateHelper.generateFile("control", context)

        def configurationFiles = task.allConfigurationFiles
        if (configurationFiles.any()) {
            debianFiles << templateHelper.generateFile("conffiles", [files: configurationFiles] )
        }

        def installUtils = task.allCommonCommands.collect { stripShebang(it) }
        def preInstall = installUtils + task.allPreInstallCommands.collect { stripShebang(it) }
        def postInstall = installUtils + task.allPostInstallCommands.collect { stripShebang(it) }
        def preUninstall = installUtils + task.allPreUninstallCommands.collect { stripShebang(it) }
        def postUninstall = installUtils + task.allPostUninstallCommands.collect { stripShebang(it) }

        def addlFiles = [preinst: preInstall, postinst: postInstall, prerm: preUninstall, postrm:postUninstall]
                .collect {
                    templateHelper.generateFile(it.key, context + [commands: it.value] )
                }
        debianFiles.addAll(addlFiles)

        task.allSupplementaryControlFiles.each { supControl ->
            File supControlFile = supControl instanceof File ? supControl : task.project.file(supControl)
            new File(debianDir, supControlFile.name).bytes = supControlFile.bytes
        }

        DebMaker maker = new DebMaker(new GradleLoggerConsole(), dataProducers, null)
        File contextFile = templateHelper.generateFile("control", context)
        maker.setControl(contextFile.parentFile)
        maker.setDeb(debFile)

        logger.info("Creating debian package: ${debFile}")

        try {
            logger.info("Creating debian package: ${debFile}")
            maker.createDeb(Compression.GZIP)
        } catch (Exception e) {
            throw new GradleException("Can't build debian package ${debFile}", e)
        }

        // TODO Put changes file into a separate task
        //def changesFile = new File("${packagePath}_all.changes")
        //createChanges(pkg, changesFile, descriptor, processor)

        logger.info 'Created deb {}', debFile
    }

    private static class GradleLoggerConsole implements Console {
        @Override
        void debug(String message) {
            logger.debug(message)
        }

        @Override
        void info(String message) {
            logger.info(message)
        }

        @Override
        void warn(String message) {
            logger.warn(message)
        }
    }

    /**
     * Map to be consumed by generateFile when transforming template
     */
    def Map toContext() {
        [
                name: task.getPackageName(),
                version: task.getVersion(),
                release: task.getRelease(),
                maintainer: task.getMaintainer(),
                uploaders: task.getUploaders(),
                priority: task.getPriority(),
                epoch: task.getEpoch(),
                description: task.getPackageDescription() ?: '',
                distribution: task.getDistribution(),
                summary: task.getSummary(),
                section: task.getPackageGroup(),
                time: DateFormatUtils.SMTP_DATETIME_FORMAT.format(new Date()),
                provides: task.getProvides(),
                depends: StringUtils.join(dependencies, ", "),
                url: task.getUrl(),
                arch: task.getArchString(),
                multiArch: getMultiArch(),
                conflicts: StringUtils.join(conflicts, ", "),
                recommends: StringUtils.join(recommends, ", "),
                suggests: StringUtils.join(suggests, ", "),
                enhances: StringUtils.join(enhances, ", " ),
                preDepends: StringUtils.join(preDepends, ", "),
                breaks: StringUtils.join(breaks, ", "),
                replaces: StringUtils.join(replaces, ", "),
                fullVersion: buildFullVersion(),
                customFields: getCustomFields(),

                // Uses install command for directory
                dirs: installDirs.collect { InstallDir dir ->
                    def map = [name: dir.name]
                    if(dir.user) {
                        if (dir.group) {
                            map['owner'] = "${dir.user}:${dir.group}"
                        } else {
                            map['owner'] = dir.user
                        }
                    }
                    return map
                }
        ]
    }

    private String buildFullVersion() {
        StringBuilder fullVersion = new StringBuilder()
        if (task.getEpoch() != 0) {
            fullVersion <<= task.getEpoch()
            fullVersion <<= ':'
        }
        fullVersion <<= task.getVersion()

        if(task.getRelease()) {
            fullVersion <<= '-'
            fullVersion <<= task.getRelease()
        }

        fullVersion.toString()
    }
}
