package com.firefly.boot.test.cli

import com.beust.jcommander.JCommander
import com.beust.jcommander.ParameterException
import com.firefly.boot.model.Project
import org.junit.Test
import kotlin.test.assertEquals

/**
 * @author Pengtao Qiu
 */
class TestCli {
    @Test
    fun testCommand() {
        val args = arrayOf(
            "-g", "com.abc",
            "-a", "abc-web",
            "-p", "com.abc.xx",
            "-d", "www.abc.com",
            "-j", "www.abc.com"
                          )
        val project = Project()
        JCommander.newBuilder().addObject(project).build().parse(*args)
        println(project)

        assertEquals("com.abc", project.groupId)
        assertEquals("abc-web", project.artifactId)
        assertEquals("com.abc.xx", project.packageName)
        assertEquals("www.abc.com", project.domainName)
        assertEquals("www.abc.com", project.uberJarName)
        assertEquals(".", project.outputPath)
    }

    @Test
    fun testHelp() {
        val args = listOf("-g")
        val project = Project()
        val commander = JCommander.newBuilder().addObject(project).build()
        commander.programName = "fireflyCli"


        try {
            commander.parse(*args.toTypedArray())
        } catch (e: ParameterException) {
            e.usage()
        }
        println(project)
    }


}