package com.typesafe.sbt
package packager
package docker
import Keys._
import universal._
import sbt._

trait DockerPlugin extends Plugin with UniversalPlugin {
  val Docker = config("docker") extend Universal

  private[this] final def makeDockerContent(dockerBaseImage: String, dockerBaseDirectory: String, maintainer: String, daemonUser: String, name: String, exposedPorts: Seq[Int], exposedVolumes: Seq[String]) = {
    val dockerCommands = Seq(
      Cmd("FROM", dockerBaseImage),
      Cmd("MAINTAINER", maintainer),
      Cmd("ADD", "files /"),
      Cmd("WORKDIR", "%s" format dockerBaseDirectory),
      ExecCmd("RUN", "chown", "-R", daemonUser, "."),
      Cmd("USER", daemonUser),
      ExecCmd("ENTRYPOINT", "bin/%s" format name),
      ExecCmd("CMD")
    )

    val exposeCommand: Option[CmdLike] = {
      if (exposedPorts.isEmpty)
        None
      else
        Some(Cmd("EXPOSE", exposedPorts.mkString(" ")))
    }

    // If the exposed volume does not exist, the volume is made available
    // with root ownership. This may be too strict for some directories,
    // and we lose the feature that all directories below the install path
    // can be written to by the binary. Therefore the directories are
    // created before the ownership is changed.
    val volumeCommands: Seq[CmdLike] = {
      if (exposedVolumes.isEmpty)
        Seq()
      else
        Seq(
          ExecCmd("RUN", Seq("mkdir", "-p") ++ exposedVolumes: _*),
          ExecCmd("VOLUME", exposedVolumes: _*)
        )
    }

    Dockerfile(volumeCommands ++ exposeCommand ++ dockerCommands: _*).makeContent
  }

  private[this] final def generateDockerConfig(
    dockerBaseImage: String, dockerBaseDirectory: String, maintainer: String, daemonUser: String, normalizedName: String, exposedPorts: Seq[Int], exposedVolumes: Seq[String], target: File) = {
    val dockerContent = makeDockerContent(dockerBaseImage, dockerBaseDirectory, maintainer, daemonUser, normalizedName, exposedPorts, exposedVolumes)

    val f = target / "Dockerfile"
    IO.write(f, dockerContent)
    f
  }

  def mapGenericFilesToDocker: Seq[Setting[_]] = {
    def renameDests(from: Seq[(File, String)], dest: String) = {
      for {
        (f, path) <- from
        newPath = "%s/%s" format (dest, path)
      } yield (f, newPath)
    }

    inConfig(Docker)(Seq(
      mappings <<= (mappings in Universal, defaultLinuxInstallLocation) map { (mappings, dest) =>
        renameDests(mappings, dest)
      }
    ))
  }

  def dockerSettings: Seq[Setting[_]] = Seq(
    dockerBaseImage := "dockerfile/java",
    sourceDirectory in Docker <<= sourceDirectory apply (_ / "docker"),
    target in Docker <<= target apply (_ / "docker")
  ) ++ mapGenericFilesToDocker ++ inConfig(Docker)(Seq(
      daemonUser := "daemon",
      publishArtifact := false,
      defaultLinuxInstallLocation := "/opt/docker",
      dockerExposedPorts := Seq(),
      dockerExposedVolumes := Seq(),
      dockerPackageMappings <<= (sourceDirectory) map { dir =>
        MappingsHelper contentOf dir
      },
      mappings <++= dockerPackageMappings,
      stage <<= (dockerGenerateConfig, dockerGenerateContext) map { (configFile, contextDir) => () },
      dockerGenerateContext <<= (cacheDirectory, mappings, target) map {
        (cacheDirectory, mappings, t) =>
          val contextDir = t / "files"
          stageFiles("docker")(cacheDirectory, contextDir, mappings)
          contextDir
      },
      dockerGenerateConfig <<=
        (dockerBaseImage, defaultLinuxInstallLocation, maintainer, daemonUser, normalizedName, dockerExposedPorts, dockerExposedVolumes, target) map {
          case (dockerBaseImage, baseDirectory, maintainer, daemonUser, normalizedName, exposedPorts, exposedVolumes, target) =>
            generateDockerConfig(dockerBaseImage, baseDirectory, maintainer, daemonUser, normalizedName, exposedPorts, exposedVolumes, target)
        }
    ))
}
