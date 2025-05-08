#include "rocc.h"
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <inttypes.h>

#define READIN_A     0
#define READIN_C     1
#define READIN_B     2
#define START_COMP   4
#define QUERYSTATUS  5

#define N 16
#define WIDTH 2 // 16 bits = 2 bytes

int64_t tri_diag_model(int16_t* a, int16_t* b, int16_t* c) {
    int64_t D0 = b[0];
    int64_t D1 = (int64_t)b[1]*b[0] - (int64_t)a[0]*c[0];
    int64_t D2;

    for (int i = 2; i < N; i++) {
        D2 = (int64_t)b[i] * D1 - (int64_t)a[i-1]*c[i-1]*D0;
        D0 = D1;
        D1 = D2;
        printf("D0: %ld, D1: %ld, D2: %ld\n", D0, D1, D2);
    }

    return D2;
}

int main() {
    int16_t A[N - 1] = {8, 6, -5, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1};
    int16_t B[N]     = {10, 1, 8, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1};
    int16_t C[N - 1] = {9, 4, 8, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1};

    // int16_t *dma_A = (int16_t*) aligned_alloc(2, N * sizeof(int16_t));
    // int16_t *dma_C = (int16_t*) aligned_alloc(2, N * sizeof(int16_t));
    // int16_t *dma_B = (int16_t*) aligned_alloc(2, N * sizeof(int16_t));
    // int64_t *result = (int64_t*) aligned_alloc(8, sizeof(int64_t));

    int16_t *dma_A = (int16_t*) malloc(N * sizeof(int16_t));
    int16_t *dma_C = (int16_t*) malloc(N * sizeof(int16_t));
    int16_t *dma_B = (int16_t*) malloc(N * sizeof(int16_t));
    int64_t *result = (int64_t*) malloc(sizeof(int64_t));

    printf("Allocated memory for A, B, C, and result\n");

    // Initialize A and C with padding at front (reverse and set 0 at [0])
    *((int16_t*)dma_A) = 0;
    *((int16_t*)dma_C) = 0;
    for (int i = 0; i < N - 1; i++) {
        *((int16_t*)(dma_A + (N - 1 - i))) = A[i];
        *((int16_t*)(dma_C + (N - 1 - i))) = C[i];
    }

    // Initialize B in reverse
    for (int i = 0; i < N; i++) {
        *((int16_t*)(dma_B + (N - 1 - i))) = B[i];
    }

    // Clear result memory manually
    for (int i = 0; i < sizeof(int64_t) / sizeof(int32_t); i++) {
        *((int32_t*)result + i) = 0;
    }

    // Read back to verify
    for (int i = 0; i < N; i++) {
        int16_t val = *((int16_t*)(dma_A + i));
        printf("A[%d]: %d\n", i, val);
        val = *((int16_t*)(dma_B + i));
        printf("B[%d]: %d\n", i, val);
        val = *((int16_t*)(dma_C + i));
        printf("C[%d]: %d\n", i, val);
    }
    printf("Result: %" PRId64 "\n", *result);

    printf("A: \n");
    ROCC_INSTRUCTION_SS(0, (uint64_t)dma_A, 16, READIN_A);
    asm volatile("fence");

    printf("C: \n");
    ROCC_INSTRUCTION_SS(0, (uint64_t)dma_C, 16, READIN_C);
    asm volatile("fence");

    printf("B: \n");
    ROCC_INSTRUCTION_SS(0, (uint64_t)dma_B, 16, READIN_B);
    asm volatile("fence");

    printf("Start Comp: \n");
    ROCC_INSTRUCTION_SS(0, (uint64_t)result, 0, START_COMP);
    asm volatile("fence");

    int32_t accel_result = *result;
    printf("Accelerator result: %d\n", accel_result);

    int32_t expected_result = tri_diag_model(A, B, C);
    printf("Expected result: %d\n", expected_result);

    return !(expected_result == accel_result);
}