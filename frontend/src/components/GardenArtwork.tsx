export default function GardenArtwork({ className = '' }: { className?: string }) {
  return (
    <svg className={`garden-art ${className}`} viewBox="0 0 720 610" fill="none" aria-hidden="true">
      <defs>
        <pattern id="garden-dots" width="26" height="26" patternUnits="userSpaceOnUse">
          <circle cx="2" cy="2" r="1" fill="#b9c1a9" opacity=".35" />
        </pattern>
        <linearGradient id="garden-leaf" x1="0" y1="0" x2="1" y2="1">
          <stop stopColor="#759071" />
          <stop offset="1" stopColor="#355745" />
        </linearGradient>
      </defs>
      <circle cx="380" cy="302" r="238" fill="#e9eddf" />
      <circle cx="380" cy="302" r="238" fill="url(#garden-dots)" />
      <circle cx="510" cy="146" r="66" fill="#df9b81" />
      <path
        d="M106 467C223 397 337 421 434 453C528 483 604 456 650 424"
        stroke="#c8cdb9"
        strokeWidth="1.4"
      />
      <path
        d="M126 505C257 447 389 489 509 504C560 511 608 501 644 487"
        stroke="#c8cdb9"
        strokeWidth="1.4"
      />
      <ellipse cx="369" cy="521" rx="142" ry="20" fill="#d6ddca" />
      <g className="garden-plant">
        <path
          d="M368 516C340 406 369 287 389 153M359 428C299 384 263 327 246 254M368 354C433 329 470 280 485 218"
          stroke="#426448"
          strokeWidth="7"
          strokeLinecap="round"
        />
        <path d="M377 225C326 213 311 147 352 103C405 118 426 181 377 225Z" fill="#8ea183" />
        <path
          d="M384 273C422 204 481 186 521 218C502 270 445 300 384 273Z"
          fill="url(#garden-leaf)"
        />
        <path d="M368 338C297 320 279 254 304 214C365 221 399 274 368 338Z" fill="#52785c" />
        <path d="M361 409C403 348 474 329 517 361C500 424 420 450 361 409Z" fill="#8fa584" />
        <path d="M296 369C236 383 185 343 178 289C240 271 291 306 296 369Z" fill="#a8b99b" />
        <path d="M252 281C209 243 207 194 237 168C281 198 289 241 252 281Z" fill="#6c8c68" />
        <path d="M356 470C291 465 249 423 265 387C320 384 357 420 356 470Z" fill="#c0cbb0" />
        <path
          d="M378 225L355 127M384 273L500 229M368 338L315 236M361 409L492 370M296 369L198 304M252 281L240 188"
          stroke="#f0f2e5"
          strokeWidth="1.6"
          opacity=".65"
        />
      </g>
      <path d="M555 444C559 422 571 407 587 400C588 426 577 439 555 444Z" fill="#8fa584" />
      <path d="M555 461V441" stroke="#426448" strokeWidth="3" />
      <path d="M194 467C179 459 174 445 179 432C194 439 202 454 194 467Z" fill="#8fa584" />
      <circle cx="291" cy="139" r="4" fill="#c38669" />
      <circle cx="553" cy="329" r="5" fill="#c38669" />
    </svg>
  )
}
