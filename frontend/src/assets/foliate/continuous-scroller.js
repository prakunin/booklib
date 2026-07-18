const wait = ms => new Promise(resolve => setTimeout(resolve, ms))

const clamp = (min, value, max) => Math.max(min, Math.min(value, max))

const normalizeWheelDelta = event => {
    if (event.deltaMode === WheelEvent.DOM_DELTA_LINE) return event.deltaY * 40
    if (event.deltaMode === WheelEvent.DOM_DELTA_PAGE) return event.deltaY * innerHeight
    return event.deltaY
}

const now = () => performance?.now?.() ?? Date.now()
const MOMENTUM_MAX_IDLE_MS = 90

const setStylesImportant = (el, styles) => {
    const { style } = el
    for (const [k, v] of Object.entries(styles)) style.setProperty(k, v, 'important')
}

const getBackground = doc => {
    const bodyStyle = doc.defaultView.getComputedStyle(doc.body)
    return bodyStyle.backgroundColor === 'rgba(0, 0, 0, 0)'
        && bodyStyle.backgroundImage === 'none'
        ? doc.defaultView.getComputedStyle(doc.documentElement).background
        : bodyStyle.background
}

const uncollapse = range => {
    if (!range?.collapsed) return range
    const { endOffset, endContainer } = range
    if (endContainer.nodeType === 1) {
        const node = endContainer.childNodes[endOffset]
        if (node?.nodeType === 1) return node
        return endContainer
    }
    if (endOffset + 1 < endContainer.length) range.setEnd(endContainer, endOffset + 1)
    else if (endOffset > 1) range.setStart(endContainer, endOffset - 1)
    else return endContainer.parentNode
    return range
}

const setSelectionTo = (target, collapse) => {
    let range
    if (target?.startContainer) range = target.cloneRange()
    else if (target?.nodeType) {
        range = target.ownerDocument.createRange()
        range.selectNode(target)
    }
    if (!range) return
    const sel = range.startContainer.ownerDocument.defaultView.getSelection()
    if (!sel) return
    sel.removeAllRanges()
    if (collapse === -1) range.collapse(true)
    else if (collapse === 1) range.collapse()
    sel.addRange(range)
}

class ContinuousSection {
    #iframe = document.createElement('iframe')
    #element = document.createElement('section')
    #styleBefore
    #style
    #overlayer
    #resizeObserver
    #lastTouchScreenY = 0
    #lastTouchTime = 0
    #touchVelocity = 0
    #touchTracking = false
    #touchMoved = false
    #momentumFrame = 0
    #momentumTime = 0
    constructor({ index, section, container, onScroll, onResize }) {
        this.index = index
        this.section = section
        this.container = container
        this.onScroll = onScroll
        this.onResize = onResize

        this.#iframe.setAttribute('part', 'filter')
        this.#iframe.setAttribute('sandbox', 'allow-same-origin allow-scripts')
        this.#iframe.setAttribute('scrolling', 'no')
        this.#iframe.title = ''
        Object.assign(this.#element.style, {
            boxSizing: 'border-box',
            position: 'relative',
            width: '100%',
            minHeight: '1px',
        })
        Object.assign(this.#iframe.style, {
            border: '0',
            display: 'block',
            visibility: 'hidden',
            width: '100%',
            height: '1px',
            overflow: 'hidden',
        })
        this.#element.append(this.#iframe)
    }
    get element() {
        return this.#element
    }
    get iframe() {
        return this.#iframe
    }
    get document() {
        return this.#iframe.contentDocument
    }
    get overlayer() {
        return this.#overlayer
    }
    set overlayer(overlayer) {
        this.#overlayer = overlayer
        this.#element.append(overlayer.element)
        this.#layoutOverlayer()
    }
    async load(src, styles, layout) {
        await new Promise(resolve => {
            this.#iframe.addEventListener('load', resolve, { once: true })
            this.#iframe.src = src
        })

        const doc = this.document
        this.#styleBefore = doc.createElement('style')
        this.#style = doc.createElement('style')
        doc.head.prepend(this.#styleBefore)
        doc.head.append(this.#style)
        this.setStyles(styles)
        this.render(layout)
        this.#attachInputHandlers(doc)
        this.#resizeObserver = new ResizeObserver(() => this.expand())
        this.#resizeObserver.observe(doc.body)
        await doc.fonts?.ready?.catch(() => null)
        this.expand()
        requestAnimationFrame(() => {
            this.expand()
            this.#iframe.style.visibility = 'visible'
        })
    }
    setStyles(styles) {
        if (!this.#style) return
        if (Array.isArray(styles)) {
            const [beforeStyle, style] = styles
            this.#styleBefore.textContent = beforeStyle ?? ''
            this.#style.textContent = style ?? ''
        } else this.#style.textContent = styles ?? ''
    }
    render({ margin, maxInlineSize }) {
        const doc = this.document
        if (!doc?.documentElement || !doc.body) return
        const sideMargin = Math.max(margin, 24)
        setStylesImportant(doc.documentElement, {
            'box-sizing': 'border-box',
            'width': 'auto',
            'height': 'auto',
            'min-height': '0',
            'overflow': 'hidden',
            'padding': `${margin}px ${sideMargin}px`,
            'overflow-wrap': 'break-word',
            '-webkit-line-box-contain': 'block glyphs replaced',
        })
        setStylesImportant(doc.body, {
            'box-sizing': 'border-box',
            'max-width': `${maxInlineSize}px`,
            'margin': '0 auto',
            'min-height': '0',
        })
        for (const el of doc.body.querySelectorAll('img, svg, video')) {
            setStylesImportant(el, {
                'max-width': '100%',
                'height': 'auto',
                'object-fit': 'contain',
                'page-break-inside': 'avoid',
                'break-inside': 'avoid',
                'box-sizing': 'border-box',
            })
        }
        this.expand()
    }
    expand() {
        const doc = this.document
        if (!doc?.documentElement || !doc.body) return
        const height = Math.max(
            doc.documentElement.scrollHeight,
            doc.body.scrollHeight,
            doc.documentElement.getBoundingClientRect().height,
            doc.body.getBoundingClientRect().height,
            1,
        )
        this.#iframe.style.height = `${Math.ceil(height)}px`
        this.#element.style.minHeight = `${Math.ceil(height)}px`
        this.#layoutOverlayer()
        this.onResize?.()
    }
    scrollToAnchor(anchor) {
        const rects = uncollapse(anchor)?.getClientRects?.()
        if (!rects) return this.element.offsetTop + (Number(anchor) || 0) * this.element.offsetHeight
        const rect = Array.from(rects).find(r => r.width > 0 && r.height > 0) || rects[0]
        if (!rect) return this.element.offsetTop
        const containerRect = this.container.getBoundingClientRect()
        const iframeRect = this.#iframe.getBoundingClientRect()
        return this.container.scrollTop + iframeRect.top + rect.top - containerRect.top
    }
    getVisibleRange(containerRect) {
        const doc = this.document
        if (!doc?.body) return null
        const iframeRect = this.#iframe.getBoundingClientRect()
        const top = containerRect.top
        const bottom = containerRect.bottom
        const acceptNode = node => {
            const name = node.localName?.toLowerCase()
            if (name === 'script' || name === 'style') return NodeFilter.FILTER_REJECT
            if (node.nodeType === Node.TEXT_NODE && !node.nodeValue?.trim()) {
                return NodeFilter.FILTER_SKIP
            }
            const range = doc.createRange()
            if (node.nodeType === Node.TEXT_NODE) range.selectNodeContents(node)
            else range.selectNode(node)
            const rects = range.getClientRects()
            for (const rect of rects) {
                const absoluteTop = iframeRect.top + rect.top
                const absoluteBottom = iframeRect.top + rect.bottom
                if (absoluteBottom >= top && absoluteTop <= bottom) return NodeFilter.FILTER_ACCEPT
            }
            return NodeFilter.FILTER_SKIP
        }
        const walker = doc.createTreeWalker(doc.body, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT, { acceptNode })
        const nodes = []
        for (let node = walker.nextNode(); node; node = walker.nextNode()) nodes.push(node)
        const from = nodes.find(node =>
            node.nodeType === Node.TEXT_NODE
            && node.parentElement
            && node.nodeValue?.length)
        if (!from) return null
        const range = doc.createRange()
        range.setStart(from, 0)
        range.setEnd(from, Math.min(from.nodeValue.length, 1))
        return range
    }
    #attachInputHandlers(doc) {
        doc.addEventListener('wheel', event => {
            if (event.ctrlKey || event.metaKey) return
            this.container.scrollTop += normalizeWheelDelta(event)
            event.preventDefault()
        }, { passive: false })
        doc.addEventListener('touchstart', event => {
            this.#touchTracking = event.touches.length === 1
            if (!this.#touchTracking) return
            this.#stopMomentum()
            this.#lastTouchScreenY = event.touches[0].screenY
            this.#lastTouchTime = now()
            this.#touchVelocity = 0
            this.#touchMoved = false
        }, { passive: true })
        doc.addEventListener('touchmove', event => {
            if (!this.#touchTracking || event.touches.length !== 1) return
            const selection = doc.defaultView.getSelection()
            if (selection && !selection.isCollapsed) return
            const screenY = event.touches[0].screenY
            const time = now()
            const delta = this.#lastTouchScreenY - screenY
            const elapsed = Math.max(1, time - this.#lastTouchTime)
            this.container.scrollTop += delta
            this.#touchMoved ||= Math.abs(delta) > 0.5
            this.#touchVelocity = Math.abs(delta) > 0.5
                ? (this.#touchVelocity * 0.35) + ((delta / elapsed) * 0.65)
                : 0
            this.#lastTouchScreenY = screenY
            this.#lastTouchTime = time
            event.preventDefault()
        }, { passive: false })
        doc.addEventListener('touchend', () => this.#startMomentum(), { passive: true })
        doc.addEventListener('touchcancel', () => {
            this.#touchTracking = false
            this.#stopMomentum()
        }, { passive: true })
    }
    #startMomentum() {
        if (!this.#touchTracking) return
        this.#touchTracking = false
        const idleTime = now() - this.#lastTouchTime
        const velocity = this.#touchVelocity
        this.#touchVelocity = 0
        if (!this.#touchMoved || idleTime > MOMENTUM_MAX_IDLE_MS) return
        if (Math.abs(velocity) < 0.45) return
        this.#momentumTime = now()
        this.#momentumFrame = requestAnimationFrame(() => this.#stepMomentum(velocity))
    }
    #stepMomentum(velocity) {
        const time = now()
        const elapsed = Math.min(34, time - this.#momentumTime)
        this.#momentumTime = time
        const before = this.container.scrollTop
        this.container.scrollTop += velocity * elapsed
        const atBoundary = this.container.scrollTop === before
            || this.container.scrollTop <= 0
            || this.container.scrollTop + this.container.clientHeight >= this.container.scrollHeight
        const nextVelocity = velocity * Math.pow(0.95, elapsed / 16.67)
        if (atBoundary || Math.abs(nextVelocity) < 0.03) {
            this.#momentumFrame = 0
            return
        }
        this.#momentumFrame = requestAnimationFrame(() => this.#stepMomentum(nextVelocity))
    }
    #stopMomentum() {
        if (!this.#momentumFrame) return
        cancelAnimationFrame(this.#momentumFrame)
        this.#momentumFrame = 0
    }
    #layoutOverlayer() {
        if (!this.#overlayer) return
        const height = this.#iframe.getBoundingClientRect().height
        Object.assign(this.#overlayer.element.style, {
            left: '0',
            top: '0',
            width: '100%',
            height: `${height}px`,
            margin: '0',
        })
        this.#overlayer.redraw()
    }
    destroy() {
        this.#stopMomentum()
        this.#resizeObserver?.disconnect()
        this.section?.unload?.()
    }
}

export class ContinuousScroller extends HTMLElement {
    static observedAttributes = [
        'gap', 'margin', 'max-inline-size',
    ]
    #root = this.attachShadow({ mode: 'closed' })
    #container
    #background
    #sections = []
    #styles
    #openPromise
    #scrollTimer
    #resizeTimer
    #currentIndex = 0
    #locked = false
    constructor() {
        super()
        this.#root.innerHTML = `<style>
        :host {
            display: block;
            box-sizing: border-box;
            width: 100%;
            height: 100%;
            container-type: size;
            --_margin: 40px;
            --_max-inline-size: 10000px;
        }
        #background {
            position: absolute;
            inset: 0;
            z-index: 0;
        }
        #container {
            position: relative;
            z-index: 1;
            width: 100%;
            height: 100%;
            overflow: auto;
            box-sizing: border-box;
            overscroll-behavior: contain;
            -webkit-overflow-scrolling: touch;
            scrollbar-gutter: stable both-edges;
        }
        #spine {
            width: 100%;
            min-height: 100%;
        }
        </style>
        <div id="background" part="filter"></div>
        <div id="container"><div id="spine"></div></div>`
        this.#background = this.#root.getElementById('background')
        this.#container = this.#root.getElementById('container')
        this.#container.addEventListener('scroll', () => {
            this.dispatchEvent(new Event('scroll'))
            this.#scheduleRelocate('scroll')
        }, { passive: true })
    }
    attributeChangedCallback() {
        this.#render()
    }
    get start() {
        return this.#container.scrollTop
    }
    get end() {
        return this.start + this.#container.clientHeight
    }
    get viewSize() {
        return this.#container.scrollHeight
    }
    async open(book) {
        this.bookDir = book.dir
        this.sections = book.sections
        this.#openPromise = this.#loadSections(book)
        await this.#openPromise
    }
    async #loadSections(book) {
        const spine = this.#root.getElementById('spine')
        const linearSections = book.sections
            .map((section, index) => ({ section, index }))
            .filter(({ section }) => section.linear !== 'no')

        for (const { section, index } of linearSections) {
            const item = new ContinuousSection({
                index,
                section,
                container: this.#container,
                onResize: () => this.#scheduleRelocate('resize'),
            })
            this.#sections.push(item)
            spine.append(item.element)

            try {
                const src = await section.load()
                await item.load(src, this.#styles, this.#layout())
                if (!this.#background.style.background)
                    this.#background.style.background = getBackground(item.document)
                this.dispatchEvent(new CustomEvent('load', {
                    detail: { doc: item.document, index },
                }))
                this.dispatchEvent(new CustomEvent('create-overlayer', {
                    detail: {
                        doc: item.document,
                        index,
                        attach: overlayer => item.overlayer = overlayer,
                    },
                }))
            } catch (e) {
                console.warn(e)
                console.warn(new Error(`Failed to load section ${index}`))
            }

            await wait(0)
        }
        this.#render()
        this.#scheduleRelocate('load')
    }
    #layout() {
        const style = getComputedStyle(this)
        const margin = parseFloat(style.getPropertyValue('--_margin')) || 0
        const maxInlineSize = parseFloat(style.getPropertyValue('--_max-inline-size')) || 10000
        return { margin, maxInlineSize }
    }
    #render() {
        const layout = this.#layout()
        for (const item of this.#sections) item.render(layout)
    }
    #scheduleRelocate(reason) {
        if (this.#locked) return
        if (this.#scrollTimer) clearTimeout(this.#scrollTimer)
        this.#scrollTimer = setTimeout(() => this.#relocate(reason), reason === 'scroll' ? 120 : 0)
    }
    #relocate(reason) {
        if (!this.#sections.length) return
        const center = this.#container.scrollTop + this.#container.clientHeight / 2
        const item = this.#sections.find(section =>
            section.element.offsetTop <= center
            && section.element.offsetTop + section.element.offsetHeight >= center)
            ?? this.#sections[this.#sections.length - 1]
        this.#currentIndex = item.index
        const height = Math.max(item.element.offsetHeight, 1)
        const fraction = clamp(0, (center - item.element.offsetTop) / height, 1)
        const sectionEndFraction = clamp(0, (this.#container.scrollTop + this.#container.clientHeight - item.element.offsetTop) / height, 1)
        const range = item.getVisibleRange(this.#container.getBoundingClientRect())
        this.dispatchEvent(new CustomEvent('relocate', {
            detail: { reason, range, index: item.index, fraction, sectionEndFraction },
        }))
    }
    async goTo(target) {
        await this.#openPromise
        const resolved = await target
        const item = this.#sections.find(section => section.index === resolved.index)
        if (!item) return
        const anchor = typeof resolved.anchor === 'function'
            ? resolved.anchor(item.document)
            : resolved.anchor ?? 0
        const margin = this.#layout().margin
        const offset = item.scrollToAnchor(anchor) - margin
        this.#locked = true
        this.#container.scrollTop = clamp(0, offset, this.#container.scrollHeight)
        await wait(0)
        this.#locked = false
        if (resolved.select) setSelectionTo(anchor, 0)
        this.#relocate(resolved.select ? 'selection' : 'navigation')
    }
    prev(distance) {
        this.#container.scrollBy({ top: -(distance ?? this.#container.clientHeight * 0.9), behavior: 'smooth' })
    }
    next(distance) {
        this.#container.scrollBy({ top: distance ?? this.#container.clientHeight * 0.9, behavior: 'smooth' })
    }
    prevSection() {
        const previous = this.#sections.findLast?.(section => section.index < this.#currentIndex)
        if (previous) return this.goTo({ index: previous.index })
    }
    nextSection() {
        const next = this.#sections.find(section => section.index > this.#currentIndex)
        if (next) return this.goTo({ index: next.index })
    }
    firstSection() {
        const first = this.#sections[0]
        if (first) return this.goTo({ index: first.index })
    }
    lastSection() {
        const last = this.#sections[this.#sections.length - 1]
        if (last) return this.goTo({ index: last.index })
    }
    getContents() {
        return this.#sections
            .filter(section => section.document)
            .map(section => ({
                index: section.index,
                overlayer: section.overlayer,
                doc: section.document,
            }))
    }
    setStyles(styles) {
        this.#styles = styles
        for (const item of this.#sections) item.setStyles(styles)
        if (this.#resizeTimer) clearTimeout(this.#resizeTimer)
        this.#resizeTimer = setTimeout(() => this.#render(), 0)
    }
    focusView() {
        this.#sections.find(section => section.index === this.#currentIndex)
            ?.document?.defaultView?.focus()
    }
    destroy() {
        if (this.#scrollTimer) clearTimeout(this.#scrollTimer)
        if (this.#resizeTimer) clearTimeout(this.#resizeTimer)
        for (const item of this.#sections) item.destroy()
        this.#sections = []
    }
    disconnectedCallback() {
        this.destroy()
    }
}

customElements.define('foliate-continuous-scroller', ContinuousScroller)
