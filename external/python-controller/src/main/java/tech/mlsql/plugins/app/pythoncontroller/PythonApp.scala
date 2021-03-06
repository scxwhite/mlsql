package tech.mlsql.plugins.app.pythoncontroller

import java.io.File
import java.nio.charset.Charset
import java.util.UUID

import org.apache.commons.io.FileUtils
import streaming.dsl.ScriptSQLExec
import tech.mlsql.app.CustomController
import tech.mlsql.arrow.python.PythonWorkerFactory
import tech.mlsql.arrow.python.runner.{PythonConf, PythonErrException, PythonProjectRunner}
import tech.mlsql.common.utils.lang.sc.ScalaMethodMacros
import tech.mlsql.common.utils.path.PathFun
import tech.mlsql.common.utils.serder.json.JSONTool
import tech.mlsql.dsl.CommandCollection
import tech.mlsql.ets.register.ETRegister
import tech.mlsql.runtime.AppRuntimeStore
import tech.mlsql.session.SetSession
import tech.mlsql.version.VersionCompatibility

/**
 * 15/1/2020 WilliamZhu(allwefantasy@gmail.com)
 */
class PythonApp extends tech.mlsql.app.App with VersionCompatibility {
  override def run(args: Seq[String]): Unit = {
    AppRuntimeStore.store.registerController("python", classOf[PythonController].getName)
    ETRegister.register("PythonInclude", classOf[PythonInclude].getName)
    CommandCollection.refreshCommandMapping(Map("pyInclude" -> "PythonInclude"))
  }

  override def supportedVersions: Seq[String] = Seq("1.5.0-SNAPSHOT", "1.5.0", "1.6.0-SNAPSHOT", "1.6.0")
}

class PythonController extends CustomController {
  override def run(params: Map[String, String]): String = {
    val context = ScriptSQLExec.context()
    val session = context.execListener.sparkSession

    val envSession = new SetSession(session, context.owner)

    val pythonEnvs = envSession.fetchPythonEnv match {
      case Some(ds) =>
        ds.collect().map { f =>
          if (f.k == ScalaMethodMacros.str(PythonConf.PYTHON_ENV)) {
            (f.k, f.v + " && export ARROW_PRE_0_15_IPC_FORMAT=1")
          } else {
            (f.k, f.v)
          }

        }.toMap
      case None => Map()
    }

    val envs = Map(
      ScalaMethodMacros.str(PythonConf.PY_EXECUTE_USER) -> context.owner,
      ScalaMethodMacros.str(PythonConf.PYTHON_ENV) -> "export ARROW_PRE_0_15_IPC_FORMAT=1"
    ) ++ pythonEnvs

    val scriptId = params("scriptId").toInt
    val sql = params("sql")

    val runnerConf = getSchemaAndConf(envSession) ++ configureLogConf
    val runIn = runnerConf.getOrElse("runIn", "executor")


    val res = runIn match {
      case "executor" =>
        session.sparkContext.parallelize[String](Seq(sql), 1).map { item =>
          PyRunner.runPython(item, scriptId, envs)
        }.collect().head
      case "driver" =>
        PyRunner.runPython(sql, scriptId, envs)
    }
    res

  }

  def configureLogConf() = {
    val context = ScriptSQLExec.context()
    val conf = context.execListener.sparkSession.sqlContext.getAllConfs
    val extraConfig = if (isLocalMaster(conf)) {
      Map[String, String]()
    } else {
      Map(PythonWorkerFactory.Tool.REDIRECT_IMPL -> "tech.mlsql.log.RedirectStreamsToSocketServer")
    }
    conf.filter(f => f._1.startsWith("spark.mlsql.log.driver")) ++
      Map(
        ScalaMethodMacros.str(PythonConf.PY_EXECUTE_USER) -> context.owner,
        "groupId" -> context.groupId
      ) ++ extraConfig
  }


  def getSchemaAndConf(envSession: SetSession) = {
    val runnerConf = envSession.fetchPythonRunnerConf match {
      case Some(conf) =>
        val temp = conf.collect().map(f => (f.k, f.v)).toMap
        temp
      case None => Map[String, String]()
    }
    runnerConf
  }

  def isLocalMaster(conf: Map[String, String]): Boolean = {
    //      val master = MLSQLConf.MLSQL_MASTER.readFrom(configReader).getOrElse("")
    val master = conf.getOrElse("spark.master", "")
    master == "local" || master.startsWith("local[")
  }

}

object PyRunner {
  def runPython(item: String, scriptId: Int, envs: Map[String, String]) = {
    val uuid = UUID.randomUUID().toString

    val pythonProjectCode = JSONTool.parseJson[List[FullPathAndScriptFile]](item)
    var projectName = ""
    pythonProjectCode.foreach { item =>
      val home = PathFun("/tmp/__mlsql__").add(uuid)
      val tempPath = item.path.split("/").drop(1)
      projectName = tempPath.head
      tempPath.dropRight(1).foreach(home.add(_))
      val file = new File(home.toPath)
      if (!file.exists()) {
        file.mkdirs()
      }
      FileUtils.writeStringToFile(new File(home.add(item.scriptFile.name).toPath), item.scriptFile.content, Charset.forName("utf-8"))
    }

    val currentScript = pythonProjectCode.filter(_.scriptFile.id == scriptId).head

    var withM = ""
    if (pythonProjectCode.size > 1) {
      withM = " -m "
    }

    val pythonPackage = currentScript.path.split("/").drop(2).dropRight(1).mkString(".")
    var executePythonPath = if (pythonPackage.isEmpty) {
      currentScript.scriptFile.name
    } else {
      pythonPackage + "." + currentScript.scriptFile.name
    }

    if (!withM.isEmpty) {
      executePythonPath = executePythonPath.split("\\.").dropRight(1).mkString(".")
    }


    try {
      val projectPath = PathFun("/tmp/__mlsql__").add(uuid).add(projectName).toPath
      val envCommand = envs.get(ScalaMethodMacros.str(PythonConf.PYTHON_ENV)).getOrElse("")
      val command = Seq("bash", "-c", envCommand + s" && cd ${projectPath} &&  python ${withM} ${executePythonPath}")

      val runner = new PythonProjectRunner(projectPath, envs)
      val output = runner.run(command, Map("throwErr" -> "true"))
      JSONTool.toJsonStr(output.toList)
    } catch {
      case e: PythonErrException => JSONTool.toJsonStr(e.getMessage.split("\n").toList)
      case e: Exception =>
        JSONTool.toJsonStr(e.getStackTrace.map(f => f.toString).toList)
    } finally {
      FileUtils.deleteQuietly(new File(PathFun("/tmp/__mlsql__").add(uuid).toPath))
    }

  }
}

case class FullPathAndScriptFile(path: String, scriptFile: quill_model.ScriptFile)

