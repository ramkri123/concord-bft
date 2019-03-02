#include "common/utils.hpp"
#include "common/json.hpp"
#include "common/rlp.hpp"
#include "gtest/gtest.h"

using namespace std;
namespace concord = com::vmware::concord;
using json = nlohmann::json;

namespace {
TEST(utils_test, parses_genesis_block) {
  // string genesis_test_file = "resources/genesis.json";
  // json pj = concord::parse_genesis_block(genesis_test_file);
  // int chainID = pj["config"]["chainId"];
  // ASSERT_EQ(chainID, 1);
}

// examples from https://github.com/ethereum/wiki/wiki/RLP
TEST(rlp_test, example_dog) {
  concord::RLPBuilder rlpb;
  std::vector<uint8_t> input{'d', 'o', 'g'};
  rlpb.add(input);
  std::vector<uint8_t> expect{0x83, 'd', 'o', 'g'};
  EXPECT_EQ(expect, rlpb.build());
}

TEST(rlp_test, example_cat_dog) {
  concord::RLPBuilder rlpb;
  rlpb.start_list();
  std::vector<uint8_t> input1{'d', 'o', 'g'};
  rlpb.add(input1);
  std::vector<uint8_t> input2{'c', 'a', 't'};
  rlpb.add(input2);
  std::vector<uint8_t> expect{0xc8, 0x83, 'c', 'a', 't', 0x83, 'd', 'o', 'g'};
  EXPECT_EQ(expect, rlpb.build());
}

TEST(rlp_test, example_empty_string) {
  concord::RLPBuilder rlpb;
  std::vector<uint8_t> input;
  rlpb.add(input);
  std::vector<uint8_t> expect{0x80};
  EXPECT_EQ(expect, rlpb.build());
}

TEST(rlp_test, example_empty_list) {
  concord::RLPBuilder rlpb;
  rlpb.start_list();
  std::vector<uint8_t> expect{0xc0};
  EXPECT_EQ(expect, rlpb.build());
}

TEST(rlp_test, example_integer_0) {
  concord::RLPBuilder rlpb;
  rlpb.add(0);
  std::vector<uint8_t> expect{0x80};
  EXPECT_EQ(expect, rlpb.build());
}

TEST(rlp_test, example_integer_15) {
  concord::RLPBuilder rlpb;
  rlpb.add(15);
  std::vector<uint8_t> expect{0x0f};
  EXPECT_EQ(expect, rlpb.build());
}

TEST(rlp_test, example_integer_1024) {
  concord::RLPBuilder rlpb;
  rlpb.add(1024);
  std::vector<uint8_t> expect{0x82, 0x04, 0x00};
  EXPECT_EQ(expect, rlpb.build());
}

TEST(rlp_test, example_nexted_list) {
  concord::RLPBuilder rlpb;

  // remember that RLPBuilder expects additions in reverse order, so read the
  // test case backward to understand the code: [ [], [[]], [ [], [[]] ] ]
  rlpb.start_list();
  {
    rlpb.start_list();
    {
      rlpb.start_list();
      {
        rlpb.start_list();
        rlpb.end_list();
      }
      rlpb.end_list();

      rlpb.start_list();
      rlpb.end_list();
    }
    rlpb.end_list();

    rlpb.start_list();
    {
      rlpb.start_list();
      rlpb.end_list();
    }
    rlpb.end_list();

    rlpb.start_list();
    rlpb.end_list();
  }  // implicit end
  std::vector<uint8_t> expect{0xc7, 0xc0, 0xc1, 0xc0, 0xc3, 0xc0, 0xc1, 0xc0};
  EXPECT_EQ(expect, rlpb.build());
}

TEST(rlp_test, example_lipsum) {
  concord::RLPBuilder rlpb;
  std::string str("Lorem ipsum dolor sit amet, consectetur adipisicing elit");
  std::vector<uint8_t> input(str.begin(), str.end());
  rlpb.add(input);
  std::vector<uint8_t> expect{0xb8, 0x38};
  std::copy(input.begin(), input.end(), std::back_inserter(expect));
  EXPECT_EQ(expect, rlpb.build());
}

TEST(utils_test, to_evm_uint256be_test) {
  uint64_t val = 0xabcd1234;
  evm_uint256be expected;
  concord::to_evm_uint256be(val, &expected);
  EXPECT_EQ(expected.bytes[31], 0x34);
  EXPECT_EQ(expected.bytes[30], 0x12);
  EXPECT_EQ(expected.bytes[29], 0xcd);
  EXPECT_EQ(expected.bytes[28], 0xab);
  for (int i = 0; i < 28; i++) {
    EXPECT_EQ(expected.bytes[i], 0x00);
  }
}

TEST(utils_test, from_evm_uint256be_test) {
  uint64_t expected = 0x12121212abcd1234;
  evm_uint256be val;
  for (int i = 0; i < 28; i++) {
    val.bytes[i] = 0x12;
  }
  val.bytes[28] = 0xab;
  val.bytes[29] = 0xcd;
  val.bytes[30] = 0x12;
  val.bytes[31] = 0x34;
  EXPECT_EQ(expected, concord::from_evm_uint256be(&val));
}

}  // namespace

int main(int argc, char **argv) {
  ::testing::InitGoogleTest(&argc, argv);
  return RUN_ALL_TESTS();
}