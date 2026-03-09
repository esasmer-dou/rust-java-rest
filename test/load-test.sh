#!/bin/bash
# Load Test Script for Rust-Spring Performance
# Tests all APIs (except health) with different parallelism levels
# Measures: Latency, Memory Usage, RPS

BASE_URL="http://localhost:8080"
REQUEST_COUNT=100
PARALLELISM_LEVELS=(10 25 100 1000)

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

# Results storage
RESULTS_DIR="/tmp/rust-spring-load-test"
mkdir -p "$RESULTS_DIR"

# Get Java process memory usage (in MB)
get_memory_usage() {
    if [[ "$OSTYPE" == "msys" || "$OSTYPE" == "win32" ]]; then
        # Windows - use wmic or PowerShell
        local pid=$(netstat -ano 2>/dev/null | grep ":8080.*LISTENING" | awk '{print $5}' | head -1 | tr -d '\r\n')
        if [ -n "$pid" ] && [ "$pid" != "0" ]; then
            # Try wmic first (faster)
            local mem=$(wmic process where "ProcessId=$pid" get WorkingSetSize 2>/dev/null | grep -E '^[0-9]' | tr -d ' \r\n')
            if [ -n "$mem" ] && [ "$mem" != "" ]; then
                echo $((mem / 1024 / 1024))
                return
            fi
            # Fallback: try PowerShell
            mem=$(powershell -Command "(Get-Process -Id $pid 2>$null).WorkingSet64" 2>/dev/null | tr -d '\r\n')
            if [ -n "$mem" ] && [ "$mem" != "" ]; then
                echo $((mem / 1024 / 1024))
                return
            fi
        fi
    else
        # Linux/Mac - use ps
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

# Run a single curl request and return time in seconds
run_single_request() {
    local method="$1"
    local endpoint="$2"
    local data="$3"

    local url="${BASE_URL}${endpoint}"

    if [ "$method" = "POST" ]; then
        curl -s -o /dev/null -w "%{time_total}" \
            -X POST \
            -H "Content-Type: application/json" \
            -d "$data" \
            "$url" 2>/dev/null
    else
        curl -s -o /dev/null -w "%{time_total}" \
            -X GET \
            "$url" 2>/dev/null
    fi
}

# Run load test for a single endpoint
run_load_test() {
    local name="$1"
    local method="$2"
    local endpoint="$3"
    local data="$4"
    local parallelism="$5"

    local output_file="$RESULTS_DIR/${name}_${parallelism}.tmp"
    local times_file="$RESULTS_DIR/times_${RANDOM}.tmp"

    > "$times_file"

    echo -e "  ${CYAN}Testing: $name (parallelism=$parallelism, requests=$REQUEST_COUNT)${NC}"

    # Record start time (in milliseconds)
    local start_time_ms=$(date +%s)000

    # Run requests in batches of parallelism
    local completed=0
    local batch=0

    while [ $completed -lt $REQUEST_COUNT ]; do
        local batch_size=$parallelism
        local remaining=$((REQUEST_COUNT - completed))
        if [ $batch_size -gt $remaining ]; then
            batch_size=$remaining
        fi

        # Run batch in parallel using subshells
        local batch_file="${times_file}.batch${batch}"
        > "$batch_file"

        for i in $(seq 1 $batch_size); do
            (
                result=$(run_single_request "$method" "$endpoint" "$data")
                echo "$result" >> "$batch_file"
            ) &
        done

        # Wait for all background jobs to complete
        wait

        # Collect results from this batch
        cat "$batch_file" >> "$times_file"
        rm -f "$batch_file"

        completed=$((completed + batch_size))
        batch=$((batch + 1))
    done

    # Record end time (in milliseconds)
    local end_time_ms=$(date +%s)000

    # Calculate total time in milliseconds
    local total_time_ms=$((end_time_ms - start_time_ms))

    # Count successful requests
    local success_count=$(wc -l < "$times_file" 2>/dev/null | tr -d ' \r\n')
    [ -z "$success_count" ] && success_count=0

    if [ "$success_count" -gt 0 ]; then
        # Calculate metrics using awk
        local metrics=$(awk '
        BEGIN {
            sum=0; count=0; min=999999; max=0;
        }
        {
            if ($1 > 0) {
                val = $1 * 1000;  # convert to ms
                sum += val;
                count++;
                if (val < min) min = val;
                if (val > max) max = val;
                values[count] = val;
            }
        }
        END {
            if (count > 0) {
                # Sort values for percentiles
                for (i = 1; i <= count; i++) {
                    for (j = i+1; j <= count; j++) {
                        if (values[j] < values[i]) {
                            tmp = values[i];
                            values[i] = values[j];
                            values[j] = tmp;
                        }
                    }
                }

                p50_idx = int(count * 0.50);
                if (p50_idx < 1) p50_idx = 1;
                p90_idx = int(count * 0.90);
                if (p90_idx < 1) p90_idx = 1;
                p99_idx = int(count * 0.99);
                if (p99_idx < 1) p99_idx = 1;

                printf "%.2f %.2f %.2f %.2f %.2f %.2f",
                    sum/count,
                    values[p50_idx],
                    values[p90_idx],
                    values[p99_idx],
                    min,
                    max;
            } else {
                print "0 0 0 0 0 0";
            }
        }' "$times_file")

        local avg_latency=$(echo "$metrics" | awk '{print $1}')
        local p50=$(echo "$metrics" | awk '{print $2}')
        local p90=$(echo "$metrics" | awk '{print $3}')
        local p99=$(echo "$metrics" | awk '{print $4}')
        local min_lat=$(echo "$metrics" | awk '{print $5}')
        local max_lat=$(echo "$metrics" | awk '{print $6}')

        # Calculate RPS
        local rps=$(awk -v req="$success_count" -v time="$total_time_ms" 'BEGIN {if(time>0) printf "%.2f", req/(time/1000); else print "0"}')

        # Get memory usage
        local mem_after=$(get_memory_usage)

        # Print results
        printf "    ${GREEN}✓${NC} RPS: ${BOLD}%-10s${NC} | Avg: %-10s ms | P50: %-10s ms | P90: %-10s ms | P99: %-10s ms\n" "$rps" "${avg_latency}" "${p50}" "${p90}" "${p99}"
        printf "      Min: %-10s ms | Max: %-10s ms | Total: %-8s ms | Success: %d/%d | Memory: %s MB\n" "${min_lat}" "${max_lat}" "$total_time_ms" "$success_count" "$REQUEST_COUNT" "$mem_after"

        # Save for summary
        echo "${rps}|${avg_latency}|${p99}|${mem_after}" > "$output_file"
    else
        echo -e "    ${RED}✗ Failed to complete requests${NC}"
        echo "0|0|0|0" > "$output_file"
    fi

    # Cleanup
    rm -f "$times_file"
}

# Test all endpoints at a given parallelism level
test_all_endpoints() {
    local parallelism="$1"

    echo ""
    echo -e "${BOLD}═══════════════════════════════════════════════════════════════${NC}"
    echo -e "${BOLD}  PARALLELISM: $parallelism concurrent requests${NC}"
    echo -e "${BOLD}═══════════════════════════════════════════════════════════════${NC}"

    # 1. GET /order/order
    run_load_test "GET_order" "GET" "/order/order" "" "$parallelism"
    sleep 1

    # 2. POST /order/create
    run_load_test "POST_create" "POST" "/order/create" '{"orderId":12345,"amount":99.99}' "$parallelism"
    sleep 1

    # 3. GET /order/{id} (pattern route)
    run_load_test "GET_order_id" "GET" "/order/12345" "" "$parallelism"
    sleep 1

    # 4. GET /order/search
    run_load_test "GET_search" "GET" "/order/search?status=pending&page=1" "" "$parallelism"
    sleep 1

    # 5. POST /order/cancel
    run_load_test "POST_cancel" "POST" "/order/cancel" '{"orderId":12345,"amount":50.00}' "$parallelism"
}

# Print summary table
print_summary() {
    echo ""
    echo -e "${BOLD}════════════════════════════════════════════════════════════════════════════════${NC}"
    echo -e "${BOLD}                           LOAD TEST SUMMARY                                   ${NC}"
    echo -e "${BOLD}════════════════════════════════════════════════════════════════════════════════${NC}"
    echo ""
    printf "${BOLD}%-12s %-15s %-12s %-14s %-12s %-12s${NC}\n" "Parallel" "Endpoint" "RPS" "Avg Lat(ms)" "P99(ms)" "Memory(MB)"
    echo "────────────────────────────────────────────────────────────────────────────────────"

    for parallelism in "${PARALLELISM_LEVELS[@]}"; do
        local first=1
        for endpoint in "GET_order" "POST_create" "GET_order_id" "GET_search" "POST_cancel"; do
            local result_file="$RESULTS_DIR/${endpoint}_${parallelism}.tmp"
            if [ -f "$result_file" ]; then
                local result=$(cat "$result_file")
                local rps=$(echo "$result" | cut -d'|' -f1)
                local avg=$(echo "$result" | cut -d'|' -f2)
                local p99=$(echo "$result" | cut -d'|' -f3)
                local mem=$(echo "$result" | cut -d'|' -f4)

                if [ $first -eq 1 ]; then
                    printf "%-12s %-15s %-12s %-14s %-12s %-12s\n" "$parallelism" "$endpoint" "$rps" "$avg" "$p99" "$mem"
                    first=0
                else
                    printf "%-12s %-15s %-12s %-14s %-12s %-12s\n" "" "$endpoint" "$rps" "$avg" "$p99" "$mem"
                fi
            fi
        done
        echo "────────────────────────────────────────────────────────────────────────────────────"
    done

    echo ""
}

# Main execution
main() {
    echo -e "${BOLD}╔═══════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${BOLD}║         RUST-SPRING PERFORMANCE LOAD TEST                    ║${NC}"
    echo -e "${BOLD}╚═══════════════════════════════════════════════════════════════╝${NC}"
    echo ""
    echo "Configuration:"
    echo "  - Base URL: $BASE_URL"
    echo "  - Request Count per Test: $REQUEST_COUNT"
    echo "  - Parallelism Levels: ${PARALLELISM_LEVELS[*]}"
    echo "  - Endpoints: 5 (GET_order, POST_create, GET_order_id, GET_search, POST_cancel)"
    echo "  - Total Requests: $((REQUEST_COUNT * ${#PARALLELISM_LEVELS[@]} * 5))"
    echo ""

    # Check if server is running
    echo -e "${YELLOW}Checking server availability...${NC}"
    if ! curl -s "$BASE_URL/health" > /dev/null 2>&1; then
        echo -e "${RED}ERROR: Server is not running at $BASE_URL${NC}"
        echo "Please start the server first using: ./local-run.sh"
        exit 1
    fi
    echo -e "${GREEN}Server is running!${NC}"
    echo ""

    # Get initial memory
    local initial_mem=$(get_memory_usage)
    echo "Initial Memory Usage: ${initial_mem} MB"
    echo ""

    # Run tests for each parallelism level
    for parallelism in "${PARALLELISM_LEVELS[@]}"; do
        test_all_endpoints "$parallelism"
    done

    # Print summary
    print_summary

    echo -e "${GREEN}Load test completed!${NC}"
    echo ""

    # Cleanup
    rm -rf "$RESULTS_DIR"
}

# Run main
main "$@"
