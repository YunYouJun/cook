<template>
  <div class="w-full">
    <CommonHeader>
      帮助
    </CommonHeader>

    <InstallPwa />

    <FeedbackActions />

    <div class="mx-auto max-w-md w-full rounded-2xl p-2" text-left>
      <FAQItem title="什么是模式？">
        <ul>
          <li><b>模糊匹配</b>：展示所有含当前选中任意食材的菜谱</li>
          <li><b>精准匹配</b>：展示所有含当前选中所有食材的菜谱</li>
          <li><b>生存模式</b>：展示当前选中食材即可制作的所有菜谱</li>
        </ul>
      </FAQItem>

      <FAQItem title="如何快速清空所选食材和工具?">
        <div inline-flex items-center justify-center>
          点击顶部 <div i-mdi-pot-steam-outline mx-1 inline-block /> 图标即可。
        </div>
      </FAQItem>

      <FAQItem title="是否有微信小程序?">
        因不可抗力（小程序因跳转 B 站视频而被判定为导流违规）下架。
        将不再提供小程序版本。
        <br>
        <br>
        搜索微信公众号<b>「云游君」</b>并发送<b>「做菜」</b>，也可以快速找到本网站。
      </FAQItem>

      <FAQItem title="是否有 APP?">
        <b>暂时没有开发 APP 的计划。</b>
        <br>
        但我们正在优化 <b>PWA</b> 的体验，以便您可以直接将本站添加到桌面，并享受<b>类似 APP 的体验</b>。
        <br>
        你可以使用浏览器打开，点击上方的<b>「安装到桌面」</b>或在菜单中点击<b>「添加到主屏幕」</b>。
      </FAQItem>

      <FAQItem title="未来是否会收费？">
        该项目将以免费开源的形式运营。
        <br>
        您可以考虑赞助本项目，以支持我们的开发。
        我会将其投入在周边的服务器、域名、CDN 等费用上。
        <ul mt-1>
          <li>
            <a href="https://afdian.net/a/yunyoujun" target="_blank">爱发电赞助</a>
          </li>
          <li>
            <a href="https://sponsors.yunyoujun.cn/" target="_blank">我要直接打钱！</a>
          </li>
        </ul>
      </FAQItem>

      <FAQItem title="页面无法点击、资源加载失败？">
        <blockquote>
          试试「无痕模式」是否正常？
        </blockquote>
        <br>
        <ol>
          <li>
            <b>清除 Cookie</b>
            <ol>
              <li>
                点击浏览器网址前方的 🔒 图标
              </li>
              <li>
                点击「Cookie」并清除
              </li>
            </ol>
          </li>
          <li>
            <b>强制刷新缓存</b>
            <ul>
              <li>Windows: <code>Ctrl + F5</code></li>
              <li>macOS: <code>Cmd + Shift + R</code></li>
            </ul>
          </li>
        </ol>
      </FAQItem>
    </div>
  </div>
  <BaseFooter mt-4 />
</template>
