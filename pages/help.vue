<script lang="ts" setup>
</script>

<template>
  <div class="w-full px-2 pt-8">
    <h1 text-2xl font="bold" my="4">
      帮助
    </h1>
    <div class="mx-auto max-w-md w-full rounded-2xl bg-white p-2" text-left>
      <FAQItem title="什么是模式？">
        <ul>
          <li>模糊匹配：展示所有含当前选中任意食材的菜谱</li>
          <li>精准匹配：展示所有含当前选中所有食材的菜谱</li>
          <li>生存模式：展示当前选中食材即可制作的所有菜谱</li>
        </ul>
      </FAQItem>

      <FAQItem title="如何快速清空所选食材和工具?">
        <div inline-flex items-center justify-center>
          点击顶部 <div i-mdi-pot-steam-outline mx-1 inline-block /> 图标即可。
        </div>
      </FAQItem>

      <FAQItem title="是否有 APP?">
        <b>暂时没有开发 APP 的计划。</b>
        <br>
        但我们正在优化 PWA 的体验，以便您可以直接将本站添加到桌面，并享受类似 APP 的体验。
        <br>
        敬请期待！
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

      <FAQItem title="其他相关链接">
        <ul>
          <li>
            相关链接
            <ul>
              <li>
                <a href="https://docs.qq.com/form/page/DWk9GWW9oTmlXZU9V" target="_blank">居家菜谱投稿</a>
              </li>
              <li>
                <a href="https://docs.qq.com/sheet/DQk1vdkhFV0twQVNS?tab=dmeahc" target="_blank">晒晒你的菜</a>
              </li>
              <li>
                <a href="https://docs.qq.com/sheet/DQk1vdkhFV0twQVNS?tab=snaau2" target="_blank">反馈建议</a>
              </li>
            </ul>
          </li>
          <li>
            网站相关
            <ul>
              <li>
                故障/新功能反馈：
                <a href="https://github.com/YunYouJun/cook/issues" target="_blank">Issues</a>
              </li>
              <li>
                交流/建议/分享：
                <a href="https://github.com/YunYouJun/cook/issues" target="_blank">Discussions</a>
              </li>
            </ul>
          </li>
        </ul>
      </FAQItem>
    </div>
  </div>
</template>
