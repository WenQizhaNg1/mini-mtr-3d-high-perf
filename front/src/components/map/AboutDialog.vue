<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useDisplay } from '../../composables/useDisplay';

const props = defineProps<{ language: 'en' | 'zh' }>();
const emit = defineEmits<{ close: [] }>();
const aboutDialog = ref<HTMLDialogElement>();
const { text } = useDisplay(() => props.language);
onMounted(() => aboutDialog.value?.showModal());
</script>

<template>
    <dialog ref="aboutDialog" class="about-overlay" @click.self="emit('close')" @cancel="emit('close')" :aria-label="text('關於 mini mtr', 'About mini mtr')">
        <section class="ui-panel about-card">
            <button class="about-close" @click="emit('close')" :aria-label="text('關閉', 'Close')" autofocus>×</button>
            <h3 class="about-title">{{ text('關於 mini mtr', 'About mini mtr') }}</h3>
            <p class="about-byline">{{ text('原作者 ', 'Original author ') }}<a href="https://github.com/7gugu" target="_blank" rel="noopener noreferrer">7gugu</a></p>
            <p class="about-contact">{{ text('博客', 'Blog') }}: <a href="https://7gugu.com" target="_blank" rel="noopener noreferrer">7gugu.com</a> · {{ text('郵箱', 'Email') }}: <a href="mailto:gz7gugu@qq.com">gz7gugu@qq.com</a></p>
            <div class="about-body">
                <p>{{ text('很早以前我就接觸到 ', 'I discovered ') }}<a href="https://minitokyo3d.com/" target="_blank" rel="noopener noreferrer">Mini Tokyo 3D</a>{{ text('。那是第一次看見整座城市的軌道交通在三維地圖上自己跑起來，當時確實被震撼到了。', ' years ago. Seeing an entire city’s rail network move on a 3D map for the first time was genuinely stunning.') }}</p>
                <p>{{ text('那之後一直想：國內的軌交網絡能不能也做成這樣。技術門檻擺在那裡，構想停了很久。現在有了 AI 的幫助，我終於有能力把香港港鐵做成這套 3D 可視化 —— 這就是 mini mtr。', 'I kept wondering whether rail networks closer to home could look like that too. The technical bar stayed high for a long time. With AI’s help, I finally built this Hong Kong MTR 3D visualization — mini mtr.') }}</p>
                <p>{{ text('時刻表目前按公開班距與服務時段生成，難免和真實運行有出入。如果你手上有更準確的時間，非常歡迎告訴我，我樂意修正。', 'Timetables are generated from published headways and service windows, so they may differ from real operations. If you have more accurate times, please share them — I’m happy to fix things.') }}</p>
            </div>
        </section>
    </dialog>
</template>
