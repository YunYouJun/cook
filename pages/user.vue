<template>
  <div>
    <CommonHeader>
      我的
    </CommonHeader>

    <FeedbackActions />

    <div class="mx-auto max-w-md w-full rounded-2xl p-2" text-left>
      <FAQItem :default-open="true" title="关于">
        <div text-left>
          <ul>
            <li>
              它诞生于 2022 年 4 月，时值疫情风控期间，希望能帮助期间的伙伴根据现有食材寻找到合适的菜谱。故原名「隔离食用手册」。
            </li>
            <li>
              如今那个时期已离我们远去，故去掉「隔离」二字。但也很高兴能在这里继续与你相遇，希望它能继续发光发热，在日常生活中帮助到大家。
            </li>
            <li>
              <div class="inline-flex items-center justify-center">
                代码仓库：<a class="inline-flex items-center justify-center" href="https://github.com/YunYouJun/cook" target="_blank">
                  <div m="r-1" i-ri-github-line inline-flex />YunYouJun/cook</a>
              </div>
            </li>
            <li>
              <div class="inline-flex items-center justify-center">
                菜谱视频来源：
                <a class="inline-flex items-center text-sm text-blue-600 dark:text-blue-400" href="https://docs.qq.com/sheet/DQk1vdkhFV0twQVNS" target="_blank">
                  <div m="r-1" i-ri-bilibili-line inline-flex />
                  <span class="inline-flex">隔离食用手册大全</span>
                </a>
              </div>
            </li>
          </ul>
        </div>
      </FAQItem>

      <FAQItem title="关于我">
        <div text-left>
          我的个人微信公众号「云游君」，会分享一些生活和写的<a href="https://sponsors.yunyoujun.cn/projects" target="_blank">
            小玩具们
          </a>。

          <a inline-flex py-4 href="https://cdn.yunyoujun.cn/img/about/white-qrcode-and-search.jpg" target="_blank">
            <img src="https://cdn.yunyoujun.cn/img/about/white-qrcode-and-search.jpg">
          </a>
        </div>
        <AboutMe />
      </FAQItem>

      <FAQItem title="致谢">
        <p>
          感谢以下小伙伴为本项目提供的数据支持和 QA ！
        </p>

        <ul mt-2 text-left text-sm>
          <li>
            <a href="https://weibo.com/runny" target="_blank">Runny</a>
          </li>
          <li>
            山竹太凉
          </li>
          <li>
            leo
          </li>
          <li>
            麒麟
          </li>
          <li>
            晴方啾
          </li>
          <li>
            课代表阿伟
          </li>
        </ul>
      </FAQItem>

      <FAQItem title="赞助者们">
        <div>
          感谢至今以来所有的<a href="https://afdian.net/a/yunyoujun" class="text-purple" target="_blank">赞助者</a>们，你们的支持是我持续维护和开发新项目的动力！
        </div>
        <div pt-2>
          <a href="https://sponsors.yunyoujun.cn" target="_blank">
            <img src="https://sponsors.yunyoujun.cn/sponsors.svg">
          </a>
        </div>
      </FAQItem>
    </div>

    <BaseFooter mt-4 />
  </div>
</template>
