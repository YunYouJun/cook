<template>
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
</template>
