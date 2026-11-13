package ev.buan

import ev.buan.hostcore.validation.HostApiVersion
import org.cordis.api.HOST_API_VERSION

/**
 * 宿主当前支持的契约版本点，解析自 `host-api` 的 [HOST_API_VERSION] 常量。
 *
 * 只解析一次，而且**失败就炸**：常量写错属于构建缺陷，应当让进程起不来，
 * 而不是让每一个插件在安装时各报一次"版本不兼容"，把一个错字伪装成很多个业务问题。
 */
internal val supportedHostApi: HostApiVersion = HostApiVersion.parse(HOST_API_VERSION)
    ?: error("host-api 的 HOST_API_VERSION 不是 MAJOR.MINOR.PATCH：\"$HOST_API_VERSION\"")
