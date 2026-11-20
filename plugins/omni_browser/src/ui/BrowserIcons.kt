package com.omni.plugin.browser.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

object BrowserIcons {

    val Home: ImageVector by lazy {
        vectorIcon(
            "Home",
            "M19 21H5C4.44772 21 4 20.5523 4 20V11L1 11L11.3273 1.6115C11.7087 1.26475 12.2913 1.26475 12.6727 1.6115L23 11L20 11V20C20 20.5523 19.5523 21 19 21ZM6 19H18V9.15745L12 3.7029L6 9.15745V19Z"
        )
    }

    val ArrowBack: ImageVector by lazy {
        vectorIcon(
            "ArrowBack",
            "M7.82843 10.9999H20V12.9999H7.82843L13.1924 18.3638L11.7782 19.778L4 11.9999L11.7782 4.22168L13.1924 5.63589L7.82843 10.9999Z"
        )
    }

    val ArrowForward: ImageVector by lazy {
        vectorIcon(
            "ArrowForward",
            "M16.1716 10.9999L10.8076 5.63589L12.2218 4.22168L20 11.9999L12.2218 19.778L10.8076 18.3638L16.1716 12.9999H4V10.9999H16.1716Z"
        )
    }

    val Reload: ImageVector by lazy {
        vectorIcon(
            "Reload",
            "M5.46257 4.43262C7.21556 2.91688 9.5007 2 12 2C17.5228 2 22 6.47715 22 12C22 14.1361 21.3302 16.1158 20.1892 17.7406L17 12H20C20 7.58172 16.4183 4 12 4C9.84982 4 7.89777 4.84827 6.46023 6.22842L5.46257 4.43262ZM18.5374 19.5674C16.7844 21.0831 14.4993 22 12 22C6.47715 22 2 17.5228 2 12C2 9.86386 2.66979 7.88416 3.8108 6.25944L7 12H4C4 16.4183 7.58172 20 12 20C14.1502 20 16.1022 19.1517 17.5398 17.7716L18.5374 19.5674Z"
        )
    }

    val Plus: ImageVector by lazy {
        vectorIcon("Plus", "M11 11V5H13V11H19V13H13V19H11V13H5V11H11Z")
    }

    val Menu: ImageVector by lazy {
        vectorIcon("Menu", "M3 4H21V6H3V4ZM3 11H21V13H3V11ZM3 18H21V20H3V18Z")
    }

    val Swap: ImageVector by lazy {
        vectorIcon(
            "Swap",
            "M16.0503 12.0498L21 16.9996L16.0503 21.9493L14.636 20.5351L17.172 17.9988L4 17.9996V15.9996L17.172 15.9988L14.636 13.464L16.0503 12.0498ZM7.94975 2.0498L9.36396 3.46402L6.828 5.9988L20 5.99955V7.99955L6.828 7.9988L9.36396 10.5351L7.94975 11.9493L3 6.99955L7.94975 2.0498Z"
        )
    }

    val Tabs: ImageVector by lazy {
        vectorIcon(
            "Tabs",
            "M4 5C3.44772 5 3 5.44772 3 6V20C3 20.5523 3.44772 21 4 21H18C18.5523 21 19 20.5523 19 20V18H21C21.5523 18 22 17.5523 22 17V3C22 2.44772 21.5523 2 21 2H7C6.44772 2 6 2.44772 6 3V5H4ZM8 5V4H20V16H19V6C19 5.44772 18.5523 5 18 5H8ZM5 7H17V19H5V7Z"
        )
    }

    val Notes: ImageVector by lazy {
        vectorIcon(
            "Notes",
            "M20 22H4C3.44772 22 3 21.5523 3 21V3C3 2.44772 3.44772 2 4 2H20C20.5523 2 21 2.44772 21 3V21C21 21.5523 20.5523 22 20 22ZM19 20V4H5V20H19ZM8 7H16V9H8V7ZM8 11H16V13H8V11ZM8 15H13V17H8V15Z"
        )
    }

    val Download: ImageVector by lazy {
        vectorIcon(
            "Download",
            "M13 10H18L12 16L6 10H11V3H13V10ZM4 19H20V12H22V20C22 20.5523 21.5523 21 21 21H3C2.44772 21 2 20.5523 2 20V12H4V19Z"
        )
    }

    val Moon: ImageVector by lazy {
        vectorIcon(
            "Moon",
            "M10 7C10 10.866 13.134 14 17 14C18.9584 14 20.729 13.1957 21.9995 11.8995C22 11.933 22 11.9665 22 12C22 17.5228 17.5228 22 12 22C6.47715 22 2 17.5228 2 12C2 6.47715 6.47715 2 12 2C12.0335 2 12.067 2 12.1005 2.00049C10.8043 3.27098 10 5.04157 10 7ZM4 12C4 16.4183 7.58172 20 12 20C15.0583 20 17.7158 18.2839 19.062 15.7621C18.3945 15.9187 17.7035 16 17 16C12.0294 16 8 11.9706 8 7C8 6.29648 8.08133 5.60547 8.2379 4.938C5.71611 6.28423 4 8.9417 4 12Z"
        )
    }

    val Sun: ImageVector by lazy {
        vectorIcon(
            "Sun",
            "M12 18C8.68629 18 6 15.3137 6 12C6 8.68629 8.68629 6 12 6C15.3137 6 18 8.68629 18 12C18 15.3137 15.3137 18 12 18ZM12 16C14.2091 16 16 14.2091 16 12C16 9.79086 14.2091 8 12 8C9.79086 8 8 9.79086 8 12C8 14.2091 9.79086 16 12 16ZM11 1H13V4H11V1ZM11 20H13V23H11V20ZM3.51472 4.92893L4.92893 3.51472L7.05025 5.63604L5.63604 7.05025L3.51472 4.92893ZM16.9497 18.364L18.364 16.9497L20.4853 19.0711L19.0711 20.4853L16.9497 18.364ZM19.0711 3.51472L20.4853 4.92893L18.364 7.05025L16.9497 5.63604L19.0711 3.51472ZM5.63604 16.9497L7.05025 18.364L4.92893 20.4853L3.51472 19.0711L5.63604 16.9497ZM23 11V13H20V11H23ZM4 11V13H1V11H4Z"
        )
    }

    val Shield: ImageVector by lazy {
        vectorIcon(
            "Shield",
            "M3.78307 2.82598L12 1L20.2169 2.82598C20.6745 2.92766 21 3.33347 21 3.80217V13.7889C21 15.795 19.9974 17.6684 18.3282 18.7812L12 23L5.6718 18.7812C4.00261 17.6684 3 15.795 3 13.7889V3.80217C3 3.33347 3.32553 2.92766 3.78307 2.82598ZM5 4.60434V13.7889C5 15.1263 5.6684 16.3752 6.7812 17.1171L12 20.5963L17.2188 17.1171C18.3316 16.3752 19 15.1263 19 13.7889V4.60434L12 3.04879L5 4.60434Z"
        )
    }

    val Flame: ImageVector by lazy {
        vectorIcon(
            "Flame",
            "M12 2C12 2 17.5 6.5 17.5 12.5C17.5 16.6421 14.1421 20 10 20C6.68629 20 4 17.3137 4 14C4 10.5 6.5 7.5 8 6C8 8.5 9.5 10 11 10C10 7.5 11.5 4.5 12 2ZM13.5 13C13.5 11 12.5 9.5 12 9C11.5 10.5 10 11.5 9 11.5C8 12.5 7.5 13.5 7.5 14.5C7.5 16.433 9.067 18 11 18C12.933 18 14.5 16.433 14.5 14.5C14.5 14 14 13.4 13.5 13Z"
        )
    }

    val ArchiveBox: ImageVector by lazy {
        vectorIcon(
            "ArchiveBox",
            "M4 21C3.44772 21 3 20.5523 3 20V8H2V3C2 2.44772 2.44772 2 3 2H21C21.5523 2 22 2.44772 22 3V8H21V20C21 20.5523 20.5523 21 20 21H4ZM19 8H5V19H19V8ZM20 4H4V6H20V4ZM9 11H15V13H9V11Z"
        )
    }

    val Zoom: ImageVector by lazy {
        vectorIcon(
            "Zoom",
            "M18.031 16.6168L22.3137 20.8995L20.8995 22.3137L16.6168 18.031C15.0769 19.263 13.124 20 11 20C6.032 20 2 15.968 2 11C2 6.032 6.032 2 11 2C15.968 2 20 6.032 20 11C20 13.124 19.263 15.0769 18.031 16.6168ZM16.0247 15.8748C17.2475 14.6146 18 12.8956 18 11C18 7.1325 14.8675 4 11 4C7.1325 4 4 7.1325 4 11C4 14.8675 7.1325 18 11 18C12.8956 18 14.6146 17.2475 15.8748 16.0247L16.0247 15.8748ZM10 10V7H12V10H15V12H12V15H10V12H7V10H10Z"
        )
    }

    val StarFilled: ImageVector by lazy {
        vectorIcon(
            "StarFilled",
            "M12.0006 18.26L4.94715 22.2082L6.52248 14.2799L0.587891 8.7918L8.61493 7.84006L12.0006 0.5L15.3862 7.84006L23.4132 8.7918L17.4787 14.2799L19.054 22.2082L12.0006 18.26Z"
        )
    }

    val StarOutline: ImageVector by lazy {
        vectorIcon(
            "StarOutline",
            "M12.0006 18.26L4.94715 22.2082L6.52248 14.2799L0.587891 8.7918L8.61493 7.84006L12.0006 0.5L15.3862 7.84006L23.4132 8.7918L17.4787 14.2799L19.054 22.2082L12.0006 18.26ZM12.0006 15.968L16.2473 18.3451L15.2988 13.5717L18.8719 10.2674L14.039 9.69434L12.0006 5.27502L9.96214 9.69434L5.12921 10.2674L8.70231 13.5717L7.75383 18.3451L12.0006 15.968Z"
        )
    }

    val Lock: ImageVector by lazy {
        vectorIcon(
            "Lock",
            "M19 10H20C20.5523 10 21 10.4477 21 11V21C21 21.5523 20.5523 22 20 22H4C3.44772 22 3 21.5523 3 21V11C3 10.4477 3.44772 10 4 10H5V9C5 5.13401 8.13401 2 12 2C15.866 2 19 5.13401 19 9V10ZM5 12V20H19V12H5ZM11 14H13V18H11V14ZM17 10V9C17 6.23858 14.7614 4 12 4C9.23858 4 7 6.23858 7 9V10H17Z"
        )
    }

    val Link: ImageVector by lazy {
        vectorIcon(
            "Link",
            "M18.364 15.5355L16.9497 14.1213L18.364 12.7071C20.3166 10.7545 20.3166 7.58866 18.364 5.63604C16.4113 3.68342 13.2455 3.68342 11.2929 5.63604L9.87868 7.05025L8.46447 5.63604L9.87868 4.22183C12.6124 1.48816 17.0445 1.48816 19.7782 4.22183C22.5118 6.9555 22.5118 11.3877 19.7782 14.1213L18.364 15.5355ZM15.5355 18.364L14.1213 19.7782C11.3877 22.5118 6.9555 22.5118 4.22183 19.7782C1.48816 17.0445 1.48816 12.6124 4.22183 9.87868L5.63604 8.46447L7.05025 9.87868L5.63604 11.2929C3.68342 13.2455 3.68342 16.4113 5.63604 18.364C7.58866 20.3166 10.7545 20.3166 12.7071 18.364L14.1213 16.9497L15.5355 18.364ZM14.8284 7.75736L16.2426 9.17157L9.17157 16.2426L7.75736 14.8284L14.8284 7.75736Z"
        )
    }

    val Camera: ImageVector by lazy {
        vectorIcon(
            "Camera",
            "M9.82843 5L7.82843 7H4V19H20V7H16.1716L14.1716 5H9.82843ZM9 3H15L17 5H21C21.5523 5 22 5.44772 22 6V20C22 20.5523 21.5523 21 21 21H3C2.44772 21 2 20.5523 2 20V6C2 5.44772 2.44772 5 3 5H7L9 3ZM12 18C8.96243 18 6.5 15.5376 6.5 12.5C6.5 9.46243 8.96243 7 12 7C15.0376 7 17.5 9.46243 17.5 12.5C17.5 15.5376 15.0376 18 12 18ZM12 16C13.933 16 15.5 14.433 15.5 12.5C15.5 10.567 13.933 9 12 9C10.067 9 8.5 10.567 8.5 12.5C8.5 14.433 10.067 16 12 16Z"
        )
    }

    val CodeFolder: ImageVector by lazy {
        vectorIcon(
            "CodeFolder",
            "M20 5H11.4142L9.41421 3H4C3.44772 3 3 3.44772 3 4V20C3 20.5523 3.44772 21 4 21H20C20.5523 21 21 20.5523 21 20V6C21 5.44772 20.5523 5 20 5ZM19 19H5V5H8.58579L10.5858 7H19V19ZM11 10H13V12H11V10ZM13 12H15V14H13V12ZM11 14H13V16H11V14Z"
        )
    }

    val Terminal: ImageVector by lazy {
        vectorIcon(
            "Terminal",
            "M3 3H21C21.5523 3 22 3.44772 22 4V20C22 20.5523 21.5523 21 21 21H3C2.44772 21 2 20.5523 2 20V4C2 3.44772 2.44772 3 3 3ZM4 5V19H20V5H4ZM7.41421 8.58579L11.8284 13L7.41421 17.4142L6 16L9 13L6 10L7.41421 8.58579ZM12 15H18V17H12V15Z"
        )
    }

    val Zap: ImageVector by lazy {
        vectorIcon(
            "Zap",
            "M13 10H20L11 23V14H4L13 1V10ZM11 12V7.22L7.532 12H13V16.78L16.468 12H11Z"
        )
    }

    val Flask: ImageVector by lazy {
        vectorIcon(
            "Flask",
            "M16 2V4H15V9.34L20.13 18.21C20.58 18.99 20.02 20 19.12 20H4.88C3.98 20 3.42 18.99 3.87 18.21L9 9.34V4H8V2H16ZM13 4H11V9.87L6.3 18H17.7L13 9.87V4Z"
        )
    }

    val Bot: ImageVector by lazy {
        vectorIcon(
            "Bot",
            "M13 4.055V2H11V4.055C7.054 4.55 4 7.922 4 12V20H20V12C20 7.922 16.946 4.55 13 4.055ZM18 18H6V12C6 8.686 8.686 6 12 6C15.314 6 18 8.686 18 12V18ZM9 13C9.828 13 10.5 12.328 10.5 11.5C10.5 10.672 9.828 10 9 10C8.172 10 7.5 10.672 7.5 11.5C7.5 12.328 8.172 13 9 13ZM15 13C15.828 13 16.5 12.328 16.5 11.5C16.5 10.672 15.828 10 15 10C14.172 10 13.5 10.672 13.5 11.5C13.5 12.328 14.172 13 15 13Z"
        )
    }

    val Smartphone: ImageVector by lazy {
        vectorIcon(
            "Smartphone",
            "M7 2H17C18.1046 2 19 2.89543 19 4V20C19 21.1046 18.1046 22 17 22H7C5.89543 22 5 21.1046 5 20V4C5 2.89543 5.89543 2 7 2ZM7 4V20H17V4H7ZM12 17C12.5523 17 13 17.4477 13 18C13 18.5523 12.5523 19 12 19C11.4477 19 11 18.5523 11 18C11 17.4477 11.4477 17 12 17Z"
        )
    }

    val Diagnostics: ImageVector by lazy {
        vectorIcon(
            "Diagnostics",
            "M19 3H14.82C14.4 1.84 13.3 1 12 1C10.7 1 9.6 1.84 9.18 3H5C3.9 3 3 3.9 3 5V19C3 20.1 3.9 21 5 21H19C20.1 21 21 20.1 21 19V5C21 3.9 20.1 3 19 3ZM12 3C12.55 3 13 3.45 13 4C13 4.55 12.55 5 12 5C11.45 5 11 4.55 11 4C11 3.45 11.45 3 12 3ZM19 19H5V5H7V7H17V5H19V19ZM7 10H17V12H7V10ZM7 14H14V16H7V14Z"
        )
    }

    val Settings: ImageVector by lazy {
        vectorIcon(
            "Settings",
            "M12 1L21.5 6.5V17.5L12 23L2.5 17.5V6.5L12 1ZM12 3.311L4.5 7.653V16.347L12 20.689L19.5 16.347V7.653L12 3.311ZM12 16C9.79086 16 8 14.2091 8 12C8 9.79086 9.79086 8 12 8C14.2091 8 16 9.79086 16 12C16 14.2091 14.2091 16 12 16ZM12 14C13.1046 14 14 13.1046 14 12C14 10.8954 13.1046 10 12 10C10.8954 10 10 10.8954 10 12C10 13.1046 10.8954 14 12 14Z"
        )
    }

    val Power: ImageVector by lazy {
        vectorIcon(
            "Power",
            "M6.26489 3.80698L7.41191 5.44558C5.34875 6.89247 4 9.28873 4 12C4 16.4183 7.58172 20 12 20C16.4183 20 20 16.4183 20 12C20 9.28873 18.6512 6.89247 16.5881 5.44558L17.7351 3.80698C20.3141 5.61577 22 8.61091 22 12C22 17.5228 17.5228 22 12 22C6.47715 22 2 17.5228 2 12C2 8.61091 3.6859 5.61577 6.26489 3.80698ZM11 12V2H13V12H11Z"
        )
    }

    val LayoutGrid: ImageVector by lazy {
        vectorIcon(
            "LayoutGrid",
            "M3 3H11V11H3V3ZM5 5V9H9V5H5ZM13 3H21V11H13V3ZM15 5V9H19V5H15ZM3 13H11V21H3V13ZM5 15V19H9V15H5ZM13 13H21V21H13V13ZM15 15V19H19V15H15Z"
        )
    }

    val ChevronDown: ImageVector by lazy {
        vectorIcon(
            "ChevronDown",
            "M11.9999 13.1714L16.9497 8.22168L18.3639 9.63589L11.9999 15.9999L5.63599 9.63589L7.0502 8.22168L11.9999 13.1714Z"
        )
    }

    val Close: ImageVector by lazy {
        vectorIcon(
            "Close",
            "M12.0007 10.5865L16.9504 5.63672L18.3646 7.05093L13.4149 12.0007L18.3646 16.9504L16.9504 18.3646L12.0007 13.4149L7.05093 18.3646L5.63672 16.9504L10.5865 12.0007L5.63672 7.05093L7.05093 5.63672L12.0007 10.5865Z"
        )
    }

    val Search: ImageVector by lazy {
        vectorIcon(
            "Search",
            "M18.031 16.6168L22.3137 20.8995L20.8995 22.3137L16.6168 18.031C15.0769 19.263 13.124 20 11 20C6.032 20 2 15.968 2 11C2 6.032 6.032 2 11 2C15.968 2 20 6.032 20 11C20 13.124 19.263 15.0769 18.031 16.6168ZM16.0247 15.8748C17.2475 14.6146 18 12.8956 18 11C18 7.1325 14.8675 4 11 4C7.1325 4 4 7.1325 4 11C4 14.8675 7.1325 18 11 18C12.8956 18 14.6146 17.2475 15.8748 16.0247L16.0247 15.8748Z"
        )
    }

    val ShieldCheck: ImageVector by lazy {
        vectorIcon(
            "ShieldCheck",
            "M12 1L20.2169 2.82598C20.6745 2.92766 21 3.33347 21 3.80217V13.7889C21 15.795 19.9974 17.6684 18.3282 18.7812L12 23L5.6718 18.7812C4.00261 17.6684 3 15.795 3 13.7889V3.80217C3 3.33347 3.32553 2.92766 3.78307 2.82598L12 1ZM12 3.04879L5 4.60434V13.7889C5 15.1263 5.6684 16.3752 6.7812 17.1171L12 20.5963L17.2188 17.1171C18.3316 16.3752 19 15.1263 19 13.7889V4.60434L12 3.04879ZM16.4524 8.22183L17.8666 9.63604L11.5026 16L7.25999 11.7574L8.6742 10.3431L11.5026 13.1716L16.4524 8.22183Z"
        )
    }
    val DragHandle: ImageVector by lazy {
        vectorIcon(
            "DragHandle",
            "M9 5C9 6.1 8.1 7 7 7C5.9 7 5 6.1 5 5C5 3.9 5.9 3 7 3C8.1 3 9 3.9 9 5ZM9 12C9 13.1 8.1 14 7 14C5.9 14 5 13.1 5 12C5 10.9 5.9 10 7 10C8.1 10 9 10.9 9 12ZM9 19C9 20.1 8.1 21 7 21C5.9 21 5 20.1 5 19C5 17.9 5.9 17 7 17C8.1 17 9 17.9 9 19ZM19 5C19 6.1 18.1 7 17 7C15.9 7 15 6.1 15 5C15 3.9 15.9 3 17 3C18.1 3 19 3.9 19 5ZM19 12C19 13.1 18.1 14 17 14C15.9 14 15 13.1 15 12C15 10.9 15.9 10 17 10C18.1 10 19 10.9 19 12ZM19 19C19 20.1 18.1 21 17 21C15.9 21 15 20.1 15 19C15 17.9 15.9 17 17 17C18.1 17 19 17.9 19 19Z"
        )
    }
    val VolumeUp: ImageVector by lazy {
        vectorIcon(
            "VolumeUp",
            "M5.88889 16H2C1.44772 16 1 15.5523 1 15V9C1 8.44772 1.44772 8 2 8H5.88889L11.1834 3.66815C11.3971 3.49335 11.7121 3.52485 11.8869 3.73857C11.9601 3.82811 12 3.94017 12 4.05589V19.9441C12 20.2203 11.7761 20.4441 11.5 20.4441C11.3843 20.4441 11.2722 20.4042 11.1834 20.3319L5.88889 16ZM3 14H6.5999L10 16.7819V7.2181L6.5999 10H3V14ZM19.4574 4.54264C21.6531 6.73832 23 9.77203 23 13.1235C23 16.475 21.6531 19.5087 19.4574 21.7044L18.0432 20.2902C19.8748 18.4586 21 15.9248 21 13.1235C21 10.3222 19.8748 7.78844 18.0432 5.95685L19.4574 4.54264ZM15.9219 8.07817C17.2067 9.36295 18 11.1503 18 13.1235C18 15.0968 17.2067 16.8841 15.9219 18.1689L14.5077 16.7547C15.4292 15.8332 16 14.5471 16 13.1235C16 11.6999 15.4292 10.4138 14.5077 9.49238L15.9219 8.07817Z"
        )
    }

    val Stop: ImageVector by lazy {
        vectorIcon(
            "Stop",
            "M6 6H18V18H6V6Z"
        )
    }

    private fun vectorIcon(name: String, pathData: String): ImageVector {
        return ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).addPath(
            pathData = addPathNodes(pathData),
            fill = SolidColor(Color.White)
        ).build()
    }
}

fun getBrowserIconForSlot(
    id: String,
    forceDark: Boolean = false,
    dataSaverMode: String = "MEDIUM",
    isFav: Boolean = false
): ImageVector = when (id) {
    "quick_toggle" -> BrowserIcons.Swap
    "tabs" -> BrowserIcons.Tabs
    "menu" -> BrowserIcons.Menu
    "home" -> BrowserIcons.Home
    "back" -> BrowserIcons.ArrowBack
    "forward" -> BrowserIcons.ArrowForward
    "reload" -> BrowserIcons.Reload
    "new_tab" -> BrowserIcons.Plus
    "smart_notes" -> BrowserIcons.Notes
    "downloads" -> BrowserIcons.Download
    "force_dark" -> if (forceDark) BrowserIcons.Sun else BrowserIcons.Moon
    "data_saver" -> if (dataSaverMode == "EXTREME") BrowserIcons.Flame else BrowserIcons.Shield
    "site_cache" -> BrowserIcons.ArchiveBox
    "page_zoom" -> BrowserIcons.Zoom
    "favorite_tab" -> if (isFav) BrowserIcons.StarFilled else BrowserIcons.StarOutline
    "focus_lock" -> BrowserIcons.Lock
    "copy_url" -> BrowserIcons.Link
    "dom_snapshot" -> BrowserIcons.Camera
    "full_source" -> BrowserIcons.CodeFolder
    "local_ide" -> BrowserIcons.Terminal
    "autopilot" -> BrowserIcons.Zap
    "studio_lab" -> BrowserIcons.Flask
    "ai_automator" -> BrowserIcons.Bot
    "recents" -> BrowserIcons.Smartphone
    "diagnostics" -> BrowserIcons.Diagnostics
    "settings" -> BrowserIcons.Settings
    "exit" -> BrowserIcons.Power
    else -> BrowserIcons.LayoutGrid
}

val ALL_BROWSER_SLOT_LABELS: List<Pair<String, String>> = listOf(
    "home" to "Home / Speed Dial",
    "quick_toggle" to "Quick Toggle Tab",
    "tabs" to "Tab Switcher",
    "menu" to "Hamburger Menu",
    "smart_notes" to "Smart Notes",
    "downloads" to "Downloads",
    "back" to "Back",
    "forward" to "Forward",
    "reload" to "Reload",
    "new_tab" to "New Tab",
    "force_dark" to "Night / Light Mode",
    "data_saver" to "Data-saver Mode",
    "site_cache" to "Cache This Site",
    "page_zoom" to "Page Zoom",
    "favorite_tab" to "Favorite Tab",
    "focus_lock" to "Focus Lock",
    "copy_url" to "Copy Clean URL",
    "dom_snapshot" to "DOM Snapshot",
    "full_source" to "Export Full Source",
    "local_ide" to "Local IDE",
    "autopilot" to "Live Auto-Pilot",
    "studio_lab" to "Studio Lab",
    "ai_automator" to "AI Studio Automator",
    "recents" to "Recents Deck",
    "diagnostics" to "Diagnostics Console",
    "settings" to "General Settings",
    "exit" to "Exit Omni Chrome"
)

val BROWSER_SLOT_LABEL_MAP: Map<String, String> = ALL_BROWSER_SLOT_LABELS.toMap()