/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.internal.connection

import java.io.IOException
import java.net.HttpURLConnection
import java.net.Socket
import java.net.UnknownServiceException
import okhttp3.Address
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.internal.EMPTY_RESPONSE
import okhttp3.internal.canReuseConnectionFor
import okhttp3.internal.closeQuietly
import okhttp3.internal.connection.RoutePlanner.Plan
import okhttp3.internal.http.RealInterceptorChain
import okhttp3.internal.platform.Platform
import okhttp3.internal.toHostHeader
import okhttp3.internal.userAgent

class RealRoutePlanner(
  private val client: OkHttpClient,
  override val address: Address,
  private val call: RealCall,
  chain: RealInterceptorChain,
) : RoutePlanner {
  private val doExtensiveHealthChecks = chain.request.method != "GET"

  private var routeSelection: RouteSelector.Selection? = null
  private var routeSelector: RouteSelector? = null
  private var nextRouteToTry: Route? = null

  override fun isCanceled(): Boolean = call.isCanceled()

  // 获取连接
  @Throws(IOException::class)
  override fun plan(): Plan {
    // 打算使用 call 中的连接
    println("${RealRoutePlanner::class.java.simpleName} Step1==== 使用 call 中的连接")
    val reuseCallConnection = planReuseCallConnection()
    if (reuseCallConnection != null) return reuseCallConnection

    // Attempt to get a connection from the pool.
    println("${RealRoutePlanner::class.java.simpleName} Step2==== 使用 连接池 中的连接")
    val pooled1 = planReusePooledConnection()
    if (pooled1 != null) return pooled1

    // Do blocking calls to plan a route for a new connection.
    println("${RealRoutePlanner::class.java.simpleName} Step3==== 执行阻塞调用来计划一个新连接的路由。")
    val connect = planConnect()

    // Now that we have a set of IP addresses, make another attempt at getting a connection from
    // the pool. We have a better chance of matching thanks to connection coalescing.
    // 现在我们有了一组IP地址，再次尝试从池获取连接。由于连接合并，我们有更好的匹配机会。
    println("${RealRoutePlanner::class.java.simpleName} Step4==== 现在我们有了一组IP地址，再次尝试从池获取连接。由于连接合并，我们有更好的匹配机会。")
    val pooled2 = planReusePooledConnection(connect, connect.routes)
    if (pooled2 != null) return pooled2

    return connect
  }

  /**
   * 如果符合新交换的条件，则返回已经连接到调用的连接。
   * 如果调用的连接存在并且有资格进行另一个交换，则返回。如果它
   * 存在，但不能用于其他交换，它是关闭的，这返回null。
   *
   * Returns the connection already attached to the call if it's eligible for a new exchange.
   *
   * If the call's connection exists and is eligible for another exchange, it is returned. If it
   * exists but cannot be used for another exchange, it is closed and this returns null.
   */
  private fun planReuseCallConnection(): ReusePlan? {
    // This may be mutated by releaseConnectionNoEvents()!
    val candidate = call.connection ?: return null

    // Make sure this connection is healthy & eligible for new exchanges. If it's no longer needed
    // then we're on the hook to close it.
    //请确保此连接是健康的&符合新的交换。如果不再需要的话
    //那么我们就挂钩关闭它。
    val healthy = candidate.isHealthy(doExtensiveHealthChecks)
    val toClose: Socket? = synchronized(candidate) {
      when {
        !healthy -> {
          // 如果不健康，需要关闭该 连接
          println("${RealRoutePlanner::class.java.simpleName} 如果不健康，需要关闭该连接")
          candidate.noNewExchanges = true
          call.releaseConnectionNoEvents()
        }
        // noNewExchanges 不再有数据交换
        // url 和 host 不相同
        candidate.noNewExchanges || !sameHostAndPort(candidate.route().address.url) -> {
          println("${RealRoutePlanner::class.java.simpleName} 1、不再有数据交换 \n 2、url 和 host 不相同，需要关闭该连接")
          call.releaseConnectionNoEvents()
        }
        else -> null
      }
    }

    // If the call's connection wasn't released, reuse it. We don't call connectionAcquired() here
    // because we already acquired it.
    if (call.connection != null) {
      // 如果连接未释放，复用改连接
      check(toClose == null)
      println("${RealRoutePlanner::class.java.simpleName} ======连接未释放，复用连接喽=========")
      return ReusePlan(candidate)
    }

    // The call's connection was released.
    toClose?.closeQuietly()
    call.eventListener.connectionReleased(call, candidate)
    return null
  }

  /** Plans to make a new connection by deciding which route to try next. */
  /** 计划通过决定下一个尝试的路线来建立一个新的连接。*/
  @Throws(IOException::class)
  private fun planConnect(): ConnectPlan {
    // Use a route from a preceding coalesced connection.
    val localNextRouteToTry = nextRouteToTry
    if (localNextRouteToTry != null) {
      nextRouteToTry = null
      println("${RealRoutePlanner::class.java.simpleName}  使用来自前一个合并连接的路由。")
      return planConnectToRoute(localNextRouteToTry)
    }

    // Use a route from an existing route selection.
    val existingRouteSelection = routeSelection
    if (existingRouteSelection != null && existingRouteSelection.hasNext()) {
      println("${RealRoutePlanner::class.java.simpleName} 使用来自现有路由选择的路由。")
      return planConnectToRoute(existingRouteSelection.next())
    }

    // Decide which proxy to use, if any. This may block in ProxySelector.select().
    var newRouteSelector = routeSelector
    if (newRouteSelector == null) {
      newRouteSelector = RouteSelector(
        address = address,
        routeDatabase = call.client.routeDatabase,
        call = call,
        fastFallback = client.fastFallback,
        eventListener = call.eventListener
      )
      routeSelector = newRouteSelector
    }

    // List available IP addresses for the current proxy. This may block in Dns.lookup().
    // 列出当前代理可用的IP地址。这可能会在Dns.lookup()中被阻塞。
    if (!newRouteSelector.hasNext()) throw IOException("exhausted all routes")
    val newRouteSelection = newRouteSelector.next()
    routeSelection = newRouteSelection

    if (call.isCanceled()) throw IOException("Canceled")

    return planConnectToRoute(newRouteSelection.next(), newRouteSelection.routes)
  }

  /**
   * 返回重用池连接的计划，如果池没有连接，则返回null
   *这个地址。
   */
  /**
   * Returns a plan to reuse a pooled connection, or null if the pool doesn't have a connection for
   * this address.
   *
   * If [planToReplace] is non-null, this will swap it for a pooled connection if that pooled
   * connection uses HTTP/2. That results in fewer sockets overall and thus fewer TCP slow starts.
   */
  internal fun planReusePooledConnection(
    planToReplace: ConnectPlan? = null,
    routes: List<Route>? = null,
  ): ReusePlan? {
    val result = client.connectionPool.delegate.callAcquirePooledConnection(
      doExtensiveHealthChecks = doExtensiveHealthChecks,
      address = address,
      call = call,
      routes = routes,
      requireMultiplexed = planToReplace != null && planToReplace.isReady
    ) ?: return null

    // If we coalesced our connection, remember the replaced connection's route. That way if the
    // coalesced connection later fails we don't waste a valid route.
    if (planToReplace != null) {
      println("${RealRoutePlanner::class.java.simpleName} 如果我们合并连接，记住被替换的连接的路由。这样，如果稍后合并的连接失败，我们就不会浪费一条有效的路由。")
      nextRouteToTry = planToReplace.route
      planToReplace.closeQuietly()
    }

    call.eventListener.connectionAcquired(call, result)
    return ReusePlan(result)
  }

  /** Returns a plan for the first attempt at [route]. This throws if no plan is possible. */
  /** 返回第一次尝试[route]的计划。如果没有可行的计划，这就会发生 */
  @Throws(IOException::class)
  internal fun planConnectToRoute(route: Route, routes: List<Route>? = null): ConnectPlan {
    if (route.address.sslSocketFactory == null) {
      if (ConnectionSpec.CLEARTEXT !in route.address.connectionSpecs) {
        throw UnknownServiceException("CLEARTEXT communication not enabled for client")
      }

      val host = route.address.url.host
      if (!Platform.get().isCleartextTrafficPermitted(host)) {
        throw UnknownServiceException(
          "CLEARTEXT communication to $host not permitted by network security policy"
        )
      }
    } else {
      if (Protocol.H2_PRIOR_KNOWLEDGE in route.address.protocols) {
        throw UnknownServiceException("H2_PRIOR_KNOWLEDGE cannot be used with HTTPS")
      }
    }

    val tunnelRequest = when {
      route.requiresTunnel() -> createTunnelRequest(route)
      else -> null
    }

    return ConnectPlan(
      client = client,
      call = call,
      routePlanner = this,
      route = route,
      routes = routes,
      attempt = 0,
      tunnelRequest = tunnelRequest,
      connectionSpecIndex = -1,
      isTlsFallback = false,
    )
  }

  /**
   * Returns a request that creates a TLS tunnel via an HTTP proxy. Everything in the tunnel request
   * is sent unencrypted to the proxy server, so tunnels include only the minimum set of headers.
   * This avoids sending potentially sensitive data like HTTP cookies to the proxy unencrypted.
   *
   * In order to support preemptive authentication we pass a fake "Auth Failed" response to the
   * authenticator. This gives the authenticator the option to customize the CONNECT request. It can
   * decline to do so by returning null, in which case OkHttp will use it as-is.
   *
   * 返回通过HTTP代理创建TLS隧道的请求。隧道请求中的所有内容
   *发送到代理服务器时没有加密，所以隧道只包含最小的报头集。
   *这避免了发送潜在的敏感数据，如HTTP cookies到未加密的代理。
  ＊
   *为了支持优先认证，我们传递一个假的“Auth Failed”响应给
   *身份。这为验证者提供了自定义CONNECT请求的选项。它可以
   *通过返回null来拒绝这样做，在这种情况下OkHttp将使用它原样。
   */
  @Throws(IOException::class)
  private fun createTunnelRequest(route: Route): Request {
    val proxyConnectRequest = Request.Builder()
      .url(route.address.url)
      .method("CONNECT", null)
      .header("Host", route.address.url.toHostHeader(includeDefaultPort = true))
      .header("Proxy-Connection", "Keep-Alive") // For HTTP/1.0 proxies like Squid.
      .header("User-Agent", userAgent)
      .build()

    val fakeAuthChallengeResponse = Response.Builder()
      .request(proxyConnectRequest)
      .protocol(Protocol.HTTP_1_1)
      .code(HttpURLConnection.HTTP_PROXY_AUTH)
      .message("Preemptive Authenticate")
      .body(EMPTY_RESPONSE)
      .sentRequestAtMillis(-1L)
      .receivedResponseAtMillis(-1L)
      .header("Proxy-Authenticate", "OkHttp-Preemptive")
      .build()

    val authenticatedRequest = route.address.proxyAuthenticator
      .authenticate(route, fakeAuthChallengeResponse)

    return authenticatedRequest ?: proxyConnectRequest
  }

  override fun hasNext(failedConnection: RealConnection?): Boolean {
    if (nextRouteToTry != null) {
      return true
    }

    if (failedConnection != null) {
      val retryRoute = retryRoute(failedConnection)
      if (retryRoute != null) {
        // Lock in the route because retryRoute() is racy and we don't want to call it twice.
        nextRouteToTry = retryRoute
        return true
      }
    }

    // If we have a routes left, use 'em.
    if (routeSelection?.hasNext() == true) return true

    // If we haven't initialized the route selector yet, assume it'll have at least one route.
    val localRouteSelector = routeSelector ?: return true

    // If we do have a route selector, use its routes.
    return localRouteSelector.hasNext()
  }

  /**
   * Return the route from [connection] if it should be retried, even if the connection itself is
   * unhealthy. The biggest gotcha here is that we shouldn't reuse routes from coalesced
   * connections.
   */
  private fun retryRoute(connection: RealConnection): Route? {
    synchronized(connection) {
      if (connection.routeFailureCount != 0) return null
      if (!connection.noNewExchanges) return null // This route is still in use.
      if (!connection.route().address.url.canReuseConnectionFor(address.url)) return null
      return connection.route()
    }
  }

  override fun sameHostAndPort(url: HttpUrl): Boolean {
    val routeUrl = address.url
    return url.port == routeUrl.port && url.host == routeUrl.host
  }
}
