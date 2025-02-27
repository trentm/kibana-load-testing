package org.kibanaLoadTest.simulation.cloud

import io.gatling.core.Predef._
import io.gatling.core.structure.ScenarioBuilder
import org.kibanaLoadTest.scenario.{Canvas, Dashboard, Discover}
import org.kibanaLoadTest.simulation.BaseSimulation

class AtOnceJourney extends BaseSimulation {
  def scenarioName(module: String): String = {
    s"Cloud  atOnce $module ${appConfig.buildVersion}"
  }

  props.maxUsers = 80

  val scnDiscover: ScenarioBuilder = scenario(scenarioName("discover"))
    .exec(loginStep.pause(props.loginPause))
    .exec(Discover.load(appConfig.baseUrl, defaultHeaders))

  val scnDashboard: ScenarioBuilder = scenario(scenarioName("dashboard"))
    .exec(loginStep.pause(props.loginPause))
    .exec(Dashboard.load(appConfig.baseUrl, defaultHeaders))

  val scnCanvas: ScenarioBuilder = scenario(scenarioName("canvas"))
    .exec(loginStep.pause(props.loginPause))
    .exec(Canvas.loadWorkpad(appConfig.baseUrl, defaultHeaders))

  setUp(
    scnDiscover
      .inject(atOnceUsers(props.maxUsers), nothingFor(props.scnPause))
      .andThen(
        scnDashboard
          .inject(atOnceUsers(props.maxUsers), nothingFor(props.scnPause))
          .andThen(scnCanvas.inject(atOnceUsers(props.maxUsers)))
      )
  ).protocols(httpProtocol).maxDuration(props.simulationTimeout)

}
