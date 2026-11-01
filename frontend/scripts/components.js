import { imagePath, images, money } from "./api.js";

Vue.component("app-image", {
  props: ["src", "alt", "eager"],
  data() {
    return { failed: false };
  },
  watch: {
    src() {
      this.failed = false;
    },
  },
  computed: {
    source() {
      return this.failed
        ? "/assets/image-placeholder.svg"
        : imagePath(this.src);
    },
  },
  template:
    '<img :src="source" :alt="alt || \'\'" :loading="eager ? \'eager\' : \'lazy\'" decoding="async" referrerpolicy="no-referrer" @error="failed = true">',
});

Vue.component("post-card", {
  props: ["post"],
  methods: { images },
  template: `
    <article class="post-card">
      <a :href="'/post-detail.html?id=' + post.id" class="post-cover" :aria-label="post.title">
        <app-image :src="images(post.images)[0]" :alt="post.title"></app-image>
      </a>
      <div class="post-card-body">
        <a :href="'/post-detail.html?id=' + post.id" class="post-card-title" :title="post.title">{{ post.title }}</a>
        <div class="post-card-footer">
          <a :href="'/other-info.html?id=' + post.userId" class="author">
            <app-image :src="post.icon || '/imgs/icons/default-icon.png'" alt=""></app-image>
            <span>{{ post.name || '约球记录员' }}</span>
          </a>
          <button class="like-button" :class="{ liked: post.isLike }" :disabled="post.busy" :aria-pressed="!!post.isLike" :aria-label="(post.isLike ? '取消点赞：' : '点赞：') + post.title" @click="$emit('like', post)">
            <i class="el-icon-star-off" aria-hidden="true"></i><span>{{ post.liked || 0 }}</span>
          </button>
        </div>
      </div>
    </article>`,
});

Vue.component("rating-stars", {
  props: ["score"],
  computed: {
    fill() {
      return Math.max(0, Math.min(100, Number(this.score || 0) * 2)) + "%";
    },
  },
  template:
    '<span class="rating-stars" aria-hidden="true">★★★★★<span :style="{ width: fill }">★★★★★</span></span>',
});

Vue.component("venue-card", {
  props: ["venue", "typeName"],
  methods: { images },
  template: `
    <a class="venue-card" :href="'/venue-detail.html?id=' + venue.id">
      <div class="venue-cover"><app-image :src="images(venue.images)[0]" :alt="venue.name"></app-image><span v-if="typeName" class="image-tag">{{ typeName }}</span></div>
      <div class="venue-card-body"><h3>{{ venue.name }}</h3>
        <div class="venue-score"><rating-stars :score="venue.score"></rating-stars><strong>{{ venue.score == null ? '暂无评分' : (venue.score / 10).toFixed(1) }}</strong><span class="muted">{{ venue.comments || 0 }} 条评价</span></div>
        <p class="muted venue-address"><i class="el-icon-location-outline" aria-hidden="true"></i>{{ venue.area || venue.address }}</p>
        <div class="venue-card-bottom"><span><strong>¥{{ venue.avgPrice == null ? '—' : venue.avgPrice }}</strong> / 人</span><span class="muted">查看场馆 <i class="el-icon-right" aria-hidden="true"></i></span></div>
      </div>
    </a>`,
});

Vue.component("booking_pass-card", {
  props: ["booking_pass", "venueName", "busy"],
  methods: { money },
  computed: {
    available() {
      const v = this.booking_pass;
      if (v.orderable === false || (v.status != null && v.status !== 1)) {
        return false;
      }
      return (
        !v.type ||
        (Number(v.stock) > 0 &&
          Date.now() >= Date.parse(v.beginTime) &&
          Date.now() <= Date.parse(v.endTime))
      );
    },
    label() {
      if (this.busy) {
        return "正在提交…";
      }
      if (!this.available) {
        return "暂不可领取";
      }
      return this.booking_pass.type
        ? "限时抢购"
        : Number(this.booking_pass.payValue) === 0
          ? "立即领取"
          : "购买订场券";
    },
  },
  template: `
    <article class="booking_pass-card">
      <div class="ticket-mark"><i class="el-icon-tickets" aria-hidden="true"></i></div>
      <div class="booking_pass-main"><a v-if="venueName" :href="'/venue-detail.html?id=' + booking_pass.venueId" class="eyebrow">{{ venueName }}</a><h3>{{ booking_pass.title }}</h3><p>{{ booking_pass.subTitle || '使用规则详见场馆说明' }}</p><div class="booking_pass-price">¥<strong>{{ money(booking_pass.payValue) }}</strong><span>价值 ¥{{ money(booking_pass.actualValue) }}</span><span v-if="booking_pass.type" class="tag">限时券 · 剩余 {{ booking_pass.stock || 0 }} 张</span></div></div>
      <button class="button primary" :disabled="!available || busy" @click="$emit('claim', booking_pass)">{{ label }}</button>
    </article>`,
});
