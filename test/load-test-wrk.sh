#!/bin/bash
# Load Test Script using wrk (via WSL on Windows)
# More accurate than curl-based testing

BASE_URL="http://localhost:8080"
DURATION="10s"          # Duration per test
THREADS=4              # Number of threads
CONNECTIONS_LEVELS=(10 25 100 1000)

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

# Check if wrk is available
check_wrk() {
    if command -v wrk &>/dev/null; then
        return 0
    fi

    # Check WSL
    if command -v wsl &>/dev/null; then
        if wsl which wrk &>/dev/null; then
            return 0
        fi
    fi

    return 1
}

# Run wrk command (handles WSL)
run_wrk() {
    local url="$1"
    local connections="$2"
    local method="$3"
    local body="$4"

    if command -v wrk &>/dev/null; then
        # Native wrk
        if [ "$method" = "POST" ]; then
            wrk -t$THREADS -c$connections -d$DURATION -s "$body" "$url" 2>/dev/null
        else
            wrk -t$THREADS -c$connections -d$DURATION "$url" 2>/dev/null
        fi
    elif command -v wsl &>/dev/null; then
        # WSL wrk
        local wsl_url=$(echo "$url" | sed 's|localhost|127.0.0.1|')
        if [ "$method" = "POST" ]; then
            wsl wrk -t$THREADS -c$connections -d$DURATION -s "$body" "$wsl_url" 2>/dev/null
        else
            wsl wrk -t$THREADS -c$connections -d$DURATION "$wsl_url" 2>/dev/null
        fi
    fi
}

# Get memory usage
get_memory() {
    if command -v wsl &>/dev/null; then
        wsl cat /proc/meminfo 2>/dev/null | grep MemAvailable | awk '{print int($2/1024)}'
    elif [[ "$OSTYPE" == "msys" || "$OSTYPE" == "win32" ]]; then
        local pid=$(netstat -ano 2>/dev/null | grep ":8080.*LISTENING" | awk '{print $5}' | head -1 | tr -d '\r\n')
        if [ -n "$pid" ] && [ "$pid" != "0" ]; then
            local mem=$(wmic process where "ProcessId=$pid" get WorkingSetSize 2>/dev/null | grep -E '^[0-9]' | tr -d ' \r\n')
            if [ -n "$mem" ]; then
                echo $((mem / 1024 / 1024))
                return
            fi
        fi
    else
        local pid=$(lsof -ti:8080 2>/dev/null | head -1)
        if [ -n "$pid" ]; then
            local mem=$(ps -o rss= -p "$pid" 2>/dev/null)
            if [ -n "$mem" ]; then
                echo $((mem / 1024))
                return
            fi
        fi
    fi
    echo "N/A"
}

# Test endpoint
test_endpoint() {
    local name="$1"
    local method="$2"
    local endpoint="$3"
    local body="$4"
    local connections="$5"

    local url="${BASE_URL}${endpoint}"

    echo -e "  ${CYAN}$name${NC} (connections=$connections)"

    local result=$(run_wrk "$url" "$connections" "$method" "$body")

    if [ -n "$result" ]; then
        # Parse wrk output
        local rps=$(echo "$result" | grep "Requests/sec" | awk '{print $2}')
        local latency=$(echo "$result" | grep -A1 "Latency" | tail -1 | awk '{print $2}')
        local p99=$(echo "$result" | grep "99%" | awk '{print $2}')
        local errors=$(echo "$result" | grep "Socket errors" | awk -F: '{print $2}' | tr -d ' ')

        [ -z "$rps" ] && rps="0"
        [ -z "$latency" ] && latency="N/A"
        [ -z "$p99" ] && p99="N/A"
        [ -z "$errors" ] && errors="0"

        local mem=$(get_memory)

        printf "    ${GREEN}✓${NC} RPS: ${BOLD}%-12s${NC} | Latency: %-10s | P99: %-10s | Errors: %-5s | Memory: %s MB\n" \
            "$rps" "$latency" "$p99" "$errors" "$mem"
    else
        echo -e "    ${RED}✗ Failed${NC}"
    fi
}

# Main
main() {
    echo -e "${BOLD}╔═══════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${BOLD}║         RUST-SPRING LOAD TEST (wrk)                              ║${NC}"
    echo -e "${BOLD}╚═══════════════════════════════════════════════════════════════╝${NC}"
    echo ""

    # Check wrk availability
    echo -e "${YELLOW}Checking wrk availability...${NC}"
    if check_wrk; then
        echo -e "${GREEN}wrk is available!${NC}"
    else
        echo -e "${RED}ERROR: wrk not found!${NC}"
        echo ""
        echo "Install wrk:"
        echo "  Ubuntu/Debian: sudo apt install wrk"
        echo "  macOS: brew install wrk"
        echo "  Windows WSL: sudo apt install wrk"
        echo ""
        echo "Or use: ./load-test.sh (curl-based)"
        exit 1
    fi

    # Check server
    echo -e "${YELLOW}Checking server...${NC}"
    if ! curl -s "$BASE_URL/health" > /dev/null 2>&1; then
        echo -e "${RED}Server not running at $BASE_URL${NC}"
        exit 1
    fi
    echo -e "${GREEN}Server is running!${NC}"
    echo ""

    echo "Configuration:"
    echo "  - Duration per test: $DURATION"
    echo "  - Threads: $THREADS"
    echo "  - Connections: ${CONNECTIONS_LEVELS[*]}"
    echo ""

    local initial_mem=$(get_memory)
    echo "Initial Memory: ${initial_mem} MB"
    echo ""

    # Run tests
    for conn in "${CONNECTIONS_LEVELS[@]}"; do
        echo -e "${BOLD}═══════════════════════════════════════════════════════════════${NC}"
        echo -e "${BOLD}  CONNECTIONS: $conn${NC}"
        echo -e "${BOLD}═══════════════════════════════════════════════════════════════${NC}"

        test_endpoint "GET /order/order" "GET" "/order/order" "" "$conn"
        sleep 1

        test_endpoint "POST /order/create" "POST" "/order/create" '{"orderId":12345,"amount":99.99}' "$conn"
        sleep 1

        test_endpoint "GET /order/{id}" "GET" "/order/12345" "" "$conn"
        sleep 1

        test_endpoint "GET /order/search" "GET" "/order/search?status=pending" "" "$conn"
        sleep 1

        test_endpoint "POST /order/cancel" "POST" "/order/cancel" '{"orderId":12345,"amount":50.00}' "$conn"
        sleep 1
    done

    echo ""
    echo -e "${GREEN}Load test completed!${NC}"
}

main "$@"
